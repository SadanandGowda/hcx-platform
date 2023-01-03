package org.swasth.hcx.controllers.v1;

import kong.unirest.HttpResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.swasth.common.dto.OnboardRequest;
import org.swasth.common.dto.ParticipantResponse;
import org.swasth.common.dto.Response;
import org.swasth.common.exception.*;
import org.swasth.common.utils.Constants;
import org.swasth.common.utils.HttpUtils;
import org.swasth.common.utils.JSONUtils;
import org.swasth.common.utils.JWTUtils;
import org.swasth.hcx.controllers.BaseController;
import org.swasth.common.exception.OTPVerificationException;
import org.swasth.hcx.services.EmailService;
import org.swasth.hcx.services.SMSService;
import org.swasth.postgresql.IDatabaseService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.*;

import static org.swasth.common.response.ResponseMessage.*;
import static org.swasth.common.utils.Constants.*;

@RestController()
@RequestMapping(Constants.VERSION_PREFIX)
public class ParticipantController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(BaseController.class);

    @Value("${email.otpSub}")
    private String otpSub;

    @Value("${email.otpMsg}")
    private String otpMsg;

    @Value("${email.otpVerifyMsg}")
    private String otpVerifyMsg;

    @Value("${email.otpVerifySub}")
    private String otpVerifySub;

    @Value("${email.otpFailedMsg}")
    private String otpFailedMsg;

    @Value("${email.otpFailedSub}")
    private String otpFailedSub;

    @Value("${email.onboardingFailedMsg}")
    private String onboadingFailedMsg;

    @Value("${email.onboardingFailedSub}")
    private String onboardingFailedSub;


    @Value("${email.otpFailedWithoutLinkMsg}")
    private String otpFailedWithoutLinkMsg;

    @Value("${email.failedIdentitySub}")
    private String failedIdentitySub;

    @Value("${email.failedIdentityMsg}")
    private String failedIdentityMsg;

    @Value("${email.registryUpdateSub}")
    private String registryUpdateSub;

    @Value("${email.registryUpdateMsg}")
    private String registryUpdateMsg;

    @Value("${email.prefillUrl}")
    private String prefillUrl;

    @Value("${hcx-api.basePath}")
    private String hcxAPIBasePath;

    @Value("${postgres.onboardingOtpTable}")
    private String onboardingOtpTable;

    @Value("${postgres.onboardingTable}")
    private String onboardingTable;

    @Value("${otp.expiry}")
    private int otpExpiry;

    @Value("${otp.maxAttempt}")
    private int otpMaxAttempt;

    @Value("${env}")
    private String env;

    @Autowired
    private SMSService smsService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private IDatabaseService postgreSQLClient;

    @Autowired
    private JWTUtils jwtUtils;

    @PostMapping(PARTICIPANT_VERIFY)
    public ResponseEntity<Object> participantVerify(@RequestHeader HttpHeaders header, @RequestBody ArrayList<Map<String,Object>> body) {
        String email = "";
        try {
            OnboardRequest request = new OnboardRequest(body);
            Map<String,Object> requestBody = request.getBody();
            logger.info("Participant verification :: " + requestBody);
            Map<String, Object> participant = (Map<String, Object>) requestBody.getOrDefault(PARTICIPANT, new HashMap<>());
            email = (String) participant.getOrDefault(PRIMARY_EMAIL, "");
            Map<String,Object> output = new HashMap<>();
            if (requestBody.containsKey(JWT_TOKEN)) {
                String jwtToken = (String) requestBody.get(JWT_TOKEN);
                Map<String, Object> jwtPayload = JSONUtils.decodeBase64String(jwtToken.split("\\.")[1], Map.class);
                onboardParticipant(header, participant, (String) jwtPayload.get(ISS), (String) jwtPayload.get(SUB), (String) requestBody.get(JWT_TOKEN), output);
            } else if (requestBody.containsKey(PAYOR_CODE)) {
                onboardParticipant(header, participant, (String) requestBody.get(PAYOR_CODE), (String) requestBody.get(APPLICANT_CODE), "", output);
            } else if (requestBody.containsKey(EMAIL_OTP)) {
                email = (String) requestBody.get(PRIMARY_EMAIL);
                verifyOTP(requestBody, output);
            } else {
                updateIdentityVerificationStatus(email, "", "", PENDING);
                createParticipantAndSendOTP(header, participant, "", output);
            }
            return getSuccessResponse(new Response(output));
        } catch (Exception e) {
            if (!(e instanceof OTPVerificationException)) {
                String onboardingErrorMsg = onboadingFailedMsg;
                onboardingErrorMsg = onboardingErrorMsg.replace("ERROR_MSG", " " + e.getMessage());
                emailService.sendMail(email, onboardingFailedSub, onboardingErrorMsg);
            }
            return exceptionHandler(new Response(), e);
        }
    }

    private void onboardParticipant(HttpHeaders header, Map<String, Object> participant, String sponsorCode, String applicantCode, String jwtToken, Map<String,Object> output) throws Exception {
        Map<String,Object> sponsorDetails = getParticipant(PARTICIPANT_CODE, sponsorCode);
        if(!jwtToken.isEmpty() && !jwtUtils.isValidSignature(jwtToken, (String) sponsorDetails.get(SIGNING_CERT_PATH)))
            throw new ClientException(ErrorCodes.ERR_INVALID_JWT, "Invalid JWT token signature");
        // TODO: remove dummy getinfo and implement getinfo as post method
        //HttpResponse<String> response = HttpUtils.get(sponsorDetails.get(ENDPOINT_URL) + PARTICIPANT_GET_INFO + applicantCode);
        String email = (String) participant.get(PRIMARY_EMAIL);
        Map<String, Object> resp = participant;
        if(!StringUtils.equalsIgnoreCase((String) participant.get(PARTICIPANT_NAME), (String) resp.get(PARTICIPANT_NAME)) ||
                !StringUtils.equalsIgnoreCase(email, (String) resp.get(PRIMARY_EMAIL))){
            output.put(IDENTITY_VERIFIED, false);
            updateIdentityVerificationStatus(email, applicantCode, sponsorCode, REJECTED);
            throw new ClientException(ErrorCodes.ERR_INVALID_IDENTITY, "Identity verification failed, participant name or email is not matched with details in sponsor system");
        } else {
            output.put(IDENTITY_VERIFIED, true);
            updateIdentityVerificationStatus(email, applicantCode, sponsorCode, ACCEPTED);
            createParticipantAndSendOTP(header, participant, sponsorCode, output);
        }
    }

    private void updateIdentityVerificationStatus(String email, String applicantCode, String sponsorCode, String  status) throws Exception {
        String query = String.format("INSERT INTO %s (applicant_email,applicant_code,sponsor_code,status,createdOn,updatedOn) VALUES ('%s','%s','%s','%s',%d,%d)",
                onboardingTable, email, applicantCode, sponsorCode, status, System.currentTimeMillis(), System.currentTimeMillis());
        postgreSQLClient.execute(query);
    }

    private void createParticipantAndSendOTP(HttpHeaders header, Map<String, Object> participant, String sponsorCode, Map<String,Object> output) throws Exception {
        participant.put(ENDPOINT_URL, "http://testurl/v0.7");
        participant.put(ENCRYPTION_CERT, "https://raw.githubusercontent.com/Swasth-Digital-Health-Foundation/hcx-platform/sprint-27/hcx-apis/src/test/resources/examples/x509-self-signed-certificate.pem");
        participant.put(REGISTRY_STATUS, CREATED);
        if(((ArrayList<String>) participant.get(ROLES)).contains(PAYOR))
            participant.put(SCHEME_CODE,"default");
        Map<String, String> headersMap = new HashMap<>();
        headersMap.put(AUTHORIZATION, Objects.requireNonNull(header.get(AUTHORIZATION)).get(0));
        HttpResponse<String> createResponse = HttpUtils.post(hcxAPIBasePath + VERSION_PREFIX + PARTICIPANT_CREATE, JSONUtils.serialize(participant), headersMap);
        ParticipantResponse pcptResponse = JSONUtils.deserialize(createResponse.getBody(), ParticipantResponse.class);
        if (createResponse.getStatus() != 200) {
            throw new ClientException(pcptResponse.getError().getCode() == null ? ErrorCodes.ERR_INVALID_PARTICIPANT_DETAILS : pcptResponse.getError().getCode(), pcptResponse.getError().getMessage());
        }
        String participantCode = (String) JSONUtils.deserialize(createResponse.getBody(), Map.class).get(PARTICIPANT_CODE);
        String otpQuery = String.format("INSERT INTO %s (participant_code,primary_email,primary_mobile,email_otp,phone_otp,createdOn," +
                        "updatedOn,expiry,phone_otp_verified,email_otp_verified,status,attempt_count) VALUES ('%s','%s','%s','%s','%s',%d,%d,%d,%b,%b,'%s',%d)", onboardingOtpTable, participantCode,
                participant.get(PRIMARY_EMAIL), participant.get(PRIMARY_MOBILE), "", "", System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis(), false, false, PENDING, 0);
        postgreSQLClient.execute(otpQuery);
        sendOTP(participant);
        output.put(PARTICIPANT_CODE, participantCode);
        logger.info("OTP has been sent successfully :: participant code : " + participantCode + " :: primary email : " + participant.get(PRIMARY_EMAIL));
    }

    @PostMapping(PARTICIPANT_OTP_SEND)
    public ResponseEntity<Object> sendOTP(@RequestBody Map<String, Object> requestBody) {
        try {
            String phoneOtp = new DecimalFormat("000000").format(new Random().nextInt(999999));
            smsService.sendOTP((String) requestBody.get(PRIMARY_MOBILE), phoneOtp);
            String emailPrefillUrl = prefillUrl;
            emailPrefillUrl = emailPrefillUrl.replace("USER_MAIL", (String) requestBody.get(PRIMARY_EMAIL))
                    .replace("PHONE", (String) requestBody.get(PRIMARY_MOBILE));
            String emailOtp = new DecimalFormat("000000").format(new Random().nextInt(999999));
            String emailMsg = otpMsg;
            emailMsg = emailMsg.replace("RANDOM_CODE", emailOtp).replace("USER_LINK", emailPrefillUrl);
            emailService.sendMail((String) requestBody.get(PRIMARY_EMAIL), otpSub, emailMsg);
            String query = String.format("UPDATE %s SET phone_otp='%s',email_otp='%s',updatedOn=%d,expiry=%d WHERE primary_email='%s'",
                    onboardingOtpTable, phoneOtp, emailOtp, System.currentTimeMillis(), System.currentTimeMillis() + otpExpiry, requestBody.get(PRIMARY_EMAIL));
            postgreSQLClient.execute(query);
            return getSuccessResponse(new Response());
        } catch (Exception e) {
            return exceptionHandler(new Response(), e);
        }
    }

    public void verifyOTP(@RequestBody Map<String, Object> requestBody, Map<String,Object> output) throws Exception {
        ResultSet resultSet = null;
        boolean emailOtpVerified = false;
        boolean phoneOtpVerified = false;
        int attemptCount = 0;
        String status = FAILED;
        String email = (String) requestBody.get(PRIMARY_EMAIL);
        String participantCode = "";
        String phoneNumber = "";
        try {
            String selectQuery = String.format("SELECT * FROM %s WHERE primary_email='%s'", onboardingOtpTable, requestBody.get(PRIMARY_EMAIL));
            resultSet = (ResultSet) postgreSQLClient.executeQuery(selectQuery);
            if (resultSet.next()) {
                attemptCount = resultSet.getInt(ATTEMPT_COUNT);
                participantCode = resultSet.getString(PARTICIPANT_CODE);
                phoneNumber = resultSet.getString(PRIMARY_MOBILE);
                if(resultSet.getString("status").equals(SUCCESSFUL)) {
                    status = SUCCESSFUL;
                    throw new ClientException(ErrorCodes.ERR_INVALID_OTP, OTP_ALREADY_VERIFIED);
                }
                if (resultSet.getLong(EXPIRY) > System.currentTimeMillis()) {
                    if(attemptCount < otpMaxAttempt) {
                        if (resultSet.getString(EMAIL_OTP).equals(requestBody.get(EMAIL_OTP))) emailOtpVerified = true; else throw new ClientException("Email OTP is invalid, please try again!");
                        if (resultSet.getString(PHONE_OTP).equals(requestBody.get(PHONE_OTP))) phoneOtpVerified = true; else throw new ClientException("Phone OTP is invalid, please try again!");
                    } else {
                        throw new ClientException(ErrorCodes.ERR_INVALID_OTP, OTP_RETRY_LIMIT);
                    }
                } else {
                    throw new ClientException(ErrorCodes.ERR_INVALID_OTP, OTP_EXPIRED);
                }
            } else {
                throw new ClientException(ErrorCodes.ERR_INVALID_OTP, OTP_RECORD_NOT_EXIST);
            }
            updateOtpStatus(true, true, attemptCount, SUCCESSFUL, email);
            String otpVerifyMessage = otpVerifyMsg;
            emailService.sendMail(email, otpVerifySub, otpVerifyMessage.replace("REGISTRY_CODE", participantCode));
            output.put(EMAIL_OTP_VERIFIED, true);
            output.put(PHONE_OTP_VERIFIED, true);
            logger.info("Communication details verification is successful : " + output + " :: primary email : " + email);
        } catch (ClientException e) {
            updateOtpStatus(emailOtpVerified, phoneOtpVerified, attemptCount, status, email);
            String otpFailedMessage = otpFailedMsg;
            if (e.getMessage().contains(OTP_ALREADY_VERIFIED) || e.getMessage().contains(OTP_RECORD_NOT_EXIST)) {
                String otpFailedWithoutLinkMessage = otpFailedWithoutLinkMsg;
                emailService.sendMail(email, otpFailedSub, otpFailedWithoutLinkMessage.replace("ERROR_MSG", " " + e.getMessage()));
            } else {
                emailService.sendMail(email, otpFailedSub, otpFailedMessage.replace("ERROR_MSG", " " + e.getMessage()).replace("USER_MAIL", email).replace("PHONE", phoneNumber));
            }
            throw new OTPVerificationException(e.getErrCode(), e.getMessage());
        } finally {
            if (resultSet != null) resultSet.close();
        }
    }

    private void updateOtpStatus(boolean emailOtpVerified, boolean phoneOtpVerified, int attemptCount, String status, String email) throws Exception {
        String updateOtpQuery = String.format("UPDATE %s SET email_otp_verified=%b,phone_otp_verified=%b,status='%s',updatedOn=%d,attempt_count=%d WHERE primary_email='%s'",
                onboardingOtpTable, emailOtpVerified, phoneOtpVerified, status, System.currentTimeMillis(), attemptCount + 1, email);
        postgreSQLClient.execute(updateOtpQuery);
    }

    private Map<String, Object> getParticipant(String key, String value) throws Exception {
        HttpResponse<String> searchResponse = HttpUtils.post(hcxAPIBasePath + VERSION_PREFIX + PARTICIPANT_SEARCH, "{ \"filters\": { \"" + key + "\": { \"eq\": \" " + value + "\" } } }", new HashMap<>());
        ParticipantResponse participantResponse = JSONUtils.deserialize(searchResponse.getBody(), ParticipantResponse.class);
        if (participantResponse.getParticipants().isEmpty())
            throw new ClientException(ErrorCodes.ERR_INVALID_PARTICIPANT_CODE, INVALID_PARTICIPANT_CODE);
        return (Map<String, Object>) participantResponse.getParticipants().get(0);
    }


    @PostMapping(PARTICIPANT_ONBOARD_UPDATE)
    public ResponseEntity<Object> onboardUpdate(@RequestBody Map<String, Object> requestBody) throws SQLException {
        try {
            logger.info("Onboard update: " + requestBody);
            boolean emailOtpVerified = false;
            boolean phoneOtpVerified = false;
            String identityStatus = REJECTED;
            String jwtToken = (String) requestBody.get("jwt_token");
            Map<String, Object> payload = JSONUtils.decodeBase64String(jwtToken.split("\\.")[1], Map.class);
            Map<String, Object> participant = (Map<String, Object>) requestBody.get(PARTICIPANT);
            String email = (String) payload.get("email");
            participant.put(REGISTRY_STATUS, ACTIVE);
            Map<String, String> headersMap = new HashMap<>();
            headersMap.put(AUTHORIZATION, "Bearer " + jwtToken);

            String otpQuery = String.format("SELECT * FROM %s WHERE primary_email='%s'", onboardingOtpTable, email);
            ResultSet resultSet = (ResultSet) postgreSQLClient.executeQuery(otpQuery);
            if (resultSet.next()) {
                emailOtpVerified = resultSet.getBoolean(EMAIL_OTP_VERIFIED);
                phoneOtpVerified = resultSet.getBoolean(PHONE_OTP_VERIFIED);
            }

            String onboardingQuery = String.format("SELECT * FROM %s WHERE applicant_email='%s'", onboardingTable, email);
            ResultSet resultSet1 = (ResultSet) postgreSQLClient.executeQuery(onboardingQuery);
            if (resultSet1.next()) {
                identityStatus = resultSet1.getString("status");
            }

            if(env.equalsIgnoreCase(STAGING)) identityStatus = ACCEPTED;

            if (emailOtpVerified && phoneOtpVerified && StringUtils.equalsIgnoreCase(identityStatus, ACCEPTED)) {
                HttpResponse<String> response = HttpUtils.post(hcxAPIBasePath + VERSION_PREFIX + PARTICIPANT_UPDATE, JSONUtils.serialize(participant), headersMap);
                if (response.getStatus() == 200) {
                    logger.info("Participant details are updated successfully :: participant code : " + participant.get(PARTICIPANT_CODE));
                    emailService.sendMail(email, registryUpdateSub, registryUpdateMsg.replace("REGISTRY_CODE", (String) participant.get(PARTICIPANT_CODE)));
                    return getSuccessResponse(new Response(PARTICIPANT_CODE, participant.get(PARTICIPANT_CODE)));
                } else return responseHandler(response, (String) participant.get(PARTICIPANT_CODE));
            } else {
                logger.info("Participant details are not updated, due to failed identity verification :: participant code : " + participant.get(PARTICIPANT_CODE));
                emailService.sendMail(email, failedIdentitySub, failedIdentityMsg);
                throw new ClientException(ErrorCodes.ERR_UPDATE_PARTICIPANT_DETAILS, "Participant details are not updated, due to failed identity verification");
            }
        } catch (Exception e) {
            return exceptionHandler(new Response(), e);
        }
    }

    @PostMapping(PARTICIPANT_VERIFY_IDENTITY)
    public ResponseEntity<Object> participantIdentityVerify(@RequestBody Map<String, Object> requestBody) {
        try {
            String applicantEmail = (String) requestBody.get(PRIMARY_EMAIL);
            String status = (String) requestBody.get(REGISTRY_STATUS);
            if(!ALLOWED_ONBOARD_STATUS.contains(status))
                throw new ClientException(ErrorCodes.ERR_INVALID_ONBOARD_STATUS, "Invalid onboard status, allowed values are: " + ALLOWED_ONBOARD_STATUS);
            //Update status for the user
            String query = String.format("UPDATE %s SET status='%s',updatedOn=%d WHERE applicant_email='%s'",
                    onboardingTable, status, System.currentTimeMillis(), applicantEmail);
            postgreSQLClient.execute(query);
            return getSuccessResponse(new Response());
        } catch (Exception e) {
            return exceptionHandler(new Response(), e);
        }
    }

}