package nz.ac.auckland.integration.testing.validator;

import nz.ac.auckland.integration.testing.resource.JsonTestResource;
import nz.ac.auckland.integration.testing.resource.StaticTestResource;
import org.apache.camel.Exchange;
import org.apache.camel.TypeConversionException;
import org.apache.commons.io.FileUtils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;

/**
 * Provides a mechanism for retrieving JSON values from a file/URL/String and also
 * validating the response from a target service.
 *
 * @author David MacDonald <d.macdonald@auckland.ac.nz>
 */
public class JsonValidator implements Validator {

    private Logger logger = LoggerFactory.getLogger(JsonValidator.class);
    JsonTestResource resource;

    /**
     * @param resource The JSON resource that is to be validated against
     */
    public JsonValidator(JsonTestResource resource) {
        this.resource = resource;
    }

    /**
     * @param exchange The exchange containing the JSON string to validate
     * @return true if the Json trees match (uses the Jackson ObjectMapper to unmarshal the string and compare using Java equality)
     */
    public boolean validate(Exchange exchange) {
        String value;
        try {
            value = exchange.getIn().getBody(String.class);
        } catch (TypeConversionException e) {
            logger.warn("Error attempting to convert JSON to a String",e);
            return false;
        }
        return value != null && validate(value);
    }

    public boolean validate(String value) {
        try {
            String expectedInput = resource.getValue();

            if (value.isEmpty() || expectedInput.isEmpty()) return value.isEmpty() && expectedInput.isEmpty();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode expectedJson = mapper.readTree(value);
            JsonNode inputJson = mapper.readTree(value);
            return expectedJson.equals(inputJson);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}