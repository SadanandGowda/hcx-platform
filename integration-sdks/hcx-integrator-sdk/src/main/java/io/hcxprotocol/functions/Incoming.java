package io.hcxprotocol.functions;

import io.hcxprotocol.dto.HCXIntegrator;
import io.hcxprotocol.dto.ResponseError;
import io.hcxprotocol.helper.ValidateHelper;
import io.hcxprotocol.helper.FhirHelper;
import io.hcxprotocol.interfaces.IncomingInterface;
import io.hcxprotocol.utils.Constants;
import io.hcxprotocol.utils.JSONUtils;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.swasth.jose.jwe.JweRequest;

import java.io.*;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;


/**
 * The <b>Incoming</b> provide the methods to help in processing the JWE Payload and extract FHIR Object.
 * The implementation of this class is to process the JWE Payload, to extract the FHIR Object and validate it using HCX FHIR IG.
 */
public class Incoming implements IncomingInterface {

    private final HCXIntegrator hcxIntegrator = HCXIntegrator.getInstance();

    @Override
    public boolean processFunction(String jwePayload, HCXIntegrator.OPERATIONS operation, Map<String, Object> output) {
        Map<String, Object> error = new HashMap<>();
        boolean result = false;
        if (!validateRequest(jwePayload, operation, error)) {
            sendResponse(error, output);
        } else if (!decryptPayload(jwePayload, output)) {
            sendResponse(output, output);
        } else if (!validatePayload((String) output.get(Constants.FHIR_PAYLOAD), operation, error)) {
            sendResponse(error, output);
        } else {
            if (sendResponse(error, output)) result = true;
        }
        return result;
    }

    @Override
    public boolean validateRequest(String jwePayload, HCXIntegrator.OPERATIONS operation, Map<String, Object> error) {
        return ValidateHelper.getInstance().validateRequest(jwePayload, operation, error);
    }

    @Override
    public boolean decryptPayload(String jwePayload, Map<String, Object> output) {
        try {
            JweRequest jweRequest = new JweRequest(JSONUtils.deserialize(jwePayload, Map.class));
            jweRequest.decryptRequest(getRsaPrivateKey(hcxIntegrator.getPrivateKey()));
            output.put(Constants.HEADERS, jweRequest.getHeaders());
            output.put(Constants.FHIR_PAYLOAD, JSONUtils.serialize(jweRequest.getPayload()));
            return true;
        } catch (Exception e) {
            output.put(HCXIntegrator.ERROR_CODES.ERR_INVALID_ENCRYPTION.toString(), e.getMessage());
            return false;
        }
    }

    private static RSAPrivateKey getRsaPrivateKey(String privateKey) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        InputStream stream = new ByteArrayInputStream(privateKey.getBytes());
        Reader fileReader = new InputStreamReader(stream);
        PemReader pemReader = new PemReader(fileReader);
        PemObject pemObject = pemReader.readPemObject();
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(pemObject.getContent());
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) factory.generatePrivate(privateKeySpec);
    }

    @Override
    public boolean validatePayload(String fhirPayload, HCXIntegrator.OPERATIONS operation, Map<String,Object> error){
        return FhirHelper.validatePayload(fhirPayload, operation, error);
    }

    @Override
    public boolean sendResponse(Map<String,Object> error, Map<String,Object> output) {
        Map<String, Object> responseObj = new HashMap<>();
        responseObj.put(Constants.TIMESTAMP, System.currentTimeMillis());
        boolean result = false;
        if (error.isEmpty()) {
            Map<String, Object> headers = (Map<String, Object>) output.get(Constants.HEADERS);
            responseObj.put(Constants.API_CALL_ID, headers.get(Constants.HCX_API_CALL_ID));
            responseObj.put(Constants.CORRELATION_ID, headers.get(Constants.HCX_CORRELATION_ID));
            result = true;
        } else {
            // Fetching only the first error and constructing the error object
            String code = (String) error.keySet().toArray()[0];
            responseObj.put(Constants.ERROR, new ResponseError(code, (String) error.get(code), ""));
        }
        output.put(Constants.RESPONSE_OBJ, responseObj);
        return result;
    }
}