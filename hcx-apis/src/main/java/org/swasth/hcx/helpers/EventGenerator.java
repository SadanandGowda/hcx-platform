package org.swasth.hcx.helpers;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.swasth.common.JsonUtils;
import org.swasth.hcx.utils.Constants;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class EventGenerator {

    @Autowired
    Environment env;

    public String generatePayloadEvent(String mid, Map<String, Object> requestBody) throws JsonProcessingException {
        Map<String,Object> event = new HashMap<>();
        event.put(Constants.MID, mid);
        event.put(Constants.PAYLOAD, requestBody);
        return JsonUtils.serialize(event);
    }

    public String generateMetadataEvent(String mid, String apiAction, Map<String, Object> requestBody) throws Exception {
        Map<String,Object> event = new HashMap<>();
        List<String> protocolHeaders = env.getProperty(Constants.PROTOCOL_HEADERS_MANDATORY, List.class);
        protocolHeaders.addAll(env.getProperty(Constants.PROTOCOL_HEADERS_OPTIONAL, List.class));
        List<String> joseHeaders = env.getProperty(Constants.JOSE_HEADERS, List.class);
        HashMap<String,Object> protectedHeaders = JsonUtils.decodeBase64String((String) requestBody.get(Constants.PROTECTED), HashMap.class);
        Map<String,Object> filterJoseHeaders = new HashMap<>();
        Map<String,Object> filterProtocolHeaders = new HashMap<>();
        joseHeaders.forEach(key -> {
            if (protectedHeaders.containsKey(key))
                filterJoseHeaders.put(key, protectedHeaders.get(key));
        });
        protocolHeaders.forEach(key -> {
            if (protectedHeaders.containsKey(key))
                filterProtocolHeaders.put(key, protectedHeaders.get(key));
        });
        event.put(Constants.MID, mid);
        event.put(Constants.ETS, System.currentTimeMillis());
        event.put(Constants.ACTION, apiAction);
        event.put(Constants.HEADERS, new HashMap<>(){{
            put(Constants.JOSE, filterJoseHeaders);
            put(Constants.PROTOCOL, filterProtocolHeaders);
        }});
        event.put(Constants.LOG_DETAILS, new HashMap<>(){{
            put(Constants.CODE, "");
            put(Constants.MESSAGE, "");
            put(Constants.TRACE, "");
        }});
        event.put("status", Constants.SUBMITTED);
        return JsonUtils.serialize(event);
    }
}
