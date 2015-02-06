package nz.ac.auckland.morc;

import au.com.bytecode.opencsv.CSVReader;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import groovy.text.GStringTemplateEngine;
import groovy.text.TemplateEngine;
import nz.ac.auckland.morc.mock.MockDefinition;
import nz.ac.auckland.morc.mock.builder.*;
import nz.ac.auckland.morc.predicate.ExceptionPredicate;
import nz.ac.auckland.morc.predicate.HeadersPredicate;
import nz.ac.auckland.morc.predicate.HttpErrorPredicate;
import nz.ac.auckland.morc.processor.BodyProcessor;
import nz.ac.auckland.morc.processor.HeadersProcessor;
import nz.ac.auckland.morc.processor.MatchedResponseProcessor;
import nz.ac.auckland.morc.resource.*;
import nz.ac.auckland.morc.specification.AsyncOrchestratedTestBuilder;
import nz.ac.auckland.morc.specification.OrchestratedTestSpecification;
import nz.ac.auckland.morc.specification.SyncOrchestratedTestBuilder;
import nz.ac.auckland.morc.utility.XmlUtilities;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.builder.SimpleBuilder;
import org.apache.camel.builder.xml.XPathBuilder;
import org.apache.camel.jsonpath.JsonPathExpression;
import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.w3c.dom.Document;

import javax.xml.namespace.QName;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.*;

@RunWith(value = MorcParameterized.class)
public abstract class MorcTestBuilder extends MorcTest {

    private List<OrchestratedTestSpecification.OrchestratedTestSpecificationBuilderInit> specificationBuilders = new ArrayList<>();
    private static XmlUtilities xmlUtilities = new XmlUtilities();

    public static final QName SOAPFAULT_CLIENT = qname("http://schemas.xmlsoap.org/soap/envelope/", "Client");
    public static final QName SOAPFAULT_SERVER = qname("http://schemas.xmlsoap.org/soap/envelope/", "Server");

    protected abstract void configure();

    /**
     * @param endpointUri The endpoint URI that an asynchronous message should be sent to
     * @param description A description for the test specification that clearly identifies it
     * @return An asynchronous test specification builder with the endpoint uri and description configured
     */
    protected AsyncOrchestratedTestBuilder asyncTest(String description, String endpointUri) {
        AsyncOrchestratedTestBuilder builder = new AsyncOrchestratedTestBuilder(description, endpointUri);
        specificationBuilders.add(builder);
        return builder;
    }

    /**
     * @return A way of specifying that the next endpoint in the specification list should be asynchronous
     */
    public static Class<AsyncOrchestratedTestBuilder> asyncTest() {
        return AsyncOrchestratedTestBuilder.class;
    }

    /**
     * @param endpointUri The endpoint URI that a synchronous message should be sent to
     * @param description A description for the test specification that clearly identifies it
     * @return An synchronous test specification builder with the endpoint uri and description configured
     */
    protected SyncOrchestratedTestBuilder syncTest(String description, String endpointUri) {
        SyncOrchestratedTestBuilder builder = new SyncOrchestratedTestBuilder(description, endpointUri);
        specificationBuilders.add(builder);
        return builder;
    }

    protected SyncOrchestratedTestBuilder syncTest(String description, TestBean testBean) {
        SyncOrchestratedTestBuilder builder = new SyncOrchestratedTestBuilder(description, testBean);
        specificationBuilders.add(builder);
        return builder;
    }

    /**
     * @return A way of specifying that the next endpoint in the specification list should be synchronous
     */
    public static Class<SyncOrchestratedTestBuilder> syncTest() {
        return SyncOrchestratedTestBuilder.class;
    }

    /**
     * @return We expect messages to be totally ordered (amongst endpoints)
     */
    public static MockDefinition.OrderingType totalOrdering() {
        return MockDefinition.OrderingType.TOTAL;
    }

    /**
     * @return We expect messages to arrive at some point after we expect
     */
    public static MockDefinition.OrderingType partialOrdering() {
        return MockDefinition.OrderingType.PARTIAL;
    }

    /**
     * @return We expect messages arrive at any point of the test
     */
    public static MockDefinition.OrderingType noOrdering() {
        return MockDefinition.OrderingType.NONE;
    }

    /**
     * @param data An XML string which will be used for seeding a message, or comparing a value
     */
    public static XmlTestResource xml(String data) {
        return new XmlTestResource(xmlUtilities.getXmlAsDocument(data));
    }

    /**
     * @param file A file containing an XML document
     */
    public static XmlTestResource xml(File file) {
        return new XmlTestResource(file);
    }

    /**
     * @param url A url pointing to an XML document
     */
    public static XmlTestResource xml(URL url) {
        return new XmlTestResource(url);
    }

    public static TestResource[] xml(final InputStream... inputs) {
        TestResource[] resources = new TestResource[inputs.length];

        for (int i = 0; i < inputs.length; i++) {
            final int offset = i;
            resources[i] = new TestResource() {
                @Override
                public Object getValue() throws Exception {
                    return new XmlTestResource(inputs[offset]).getValue();
                }

                @Override
                public String toString() {
                    return "DynamicXMLTestResource";
                }
            };
        }

        return resources;
    }

    /**
     * @param data A JSON string which will be used for seeding a message, or comparing a value
     */
    public static JsonTestResource json(String data) {
        return new JsonTestResource(data);
    }

    /**
     * @param file A file containing a JSON document
     */
    public static JsonTestResource json(File file) {
        return new JsonTestResource(file);
    }

    /**
     * @param url A url pointing to a JSON document
     */
    public static JsonTestResource json(URL url) {
        return new JsonTestResource(url);
    }

    public static TestResource[] json(final InputStream... inputs) {
        TestResource[] resources = new TestResource[inputs.length];

        for (int i = 0; i < inputs.length; i++) {
            final int offset = i;
            resources[i] = new TestResource() {
                @Override
                public Object getValue() throws Exception {
                    return new JsonTestResource(inputs[offset]).getValue();
                }

                @Override
                public String toString() {
                    return "DynamicJSONTestResource";
                }
            };
        }

        return resources;
    }

    /**
     * @param data A standard Java string which will be used for seeding a message, or comparing a value
     */
    @SuppressWarnings("unchecked")
    public static PlainTextTestResource text(String data) {
        return new PlainTextTestResource(data);
    }

    /**
     * @param file A file containing plain text
     */
    @SuppressWarnings("unchecked")
    public static PlainTextTestResource text(File file) {
        return new PlainTextTestResource(file);
    }

    /**
     * @param url A url pointing to a plain text document
     */
    @SuppressWarnings("unchecked")
    public static PlainTextTestResource text(URL url) {
        return new PlainTextTestResource(url);
    }

    public static TestResource[] text(final InputStream... inputs) {
        TestResource[] resources = new TestResource[inputs.length];

        for (int i = 0; i < inputs.length; i++) {
            final int offset = i;
            resources[i] = new TestResource() {
                @Override
                public Object getValue() throws Exception {
                    return new PlainTextTestResource(inputs[offset]).getValue();
                }

                @Override
                public String toString() {
                    return "DynamicPlainTextTestResource";
                }
            };
        }

        return resources;
    }

    /**
     * A simple way of repeating the same input multiple times - useful for sending or expecting the same message
     * multiple times
     */
    @SuppressWarnings("unchecked")
    public static <T> T[] times(int count, T input) {
        T[] typeArray = (T[]) java.lang.reflect.Array.newInstance(input.getClass(), count);
        for (int i = 0; i < count; i++) {
            typeArray[i] = input;
        }

        return typeArray;
    }

    /**
     * @param data A map of headers and corresponding data that will be used for seeding a message, or comparing an expected value
     */
    public static HeadersTestResource headers(Map<String, Object> data) {
        return new HeadersTestResource(data);
    }

    /**
     * @param file A Java Properties file containing header=value values
     */
    public static HeadersTestResource headers(File file) {
        return new HeadersTestResource(file);
    }

    /**
     * @param url A pointer to a Java Properties resource containing header=value values
     */
    public static HeadersTestResource headers(URL url) {
        return new HeadersTestResource(url);
    }

    /**
     * @param inputs An InputStream reference to a headers (name=value pairs) resource
     */
    public static HeadersTestResource[] headers(final InputStream[] inputs) {
        HeadersTestResource[] resources = new HeadersTestResource[inputs.length];

        for (int i = 0; i < inputs.length; i++) {
            resources[i] = new HeadersTestResource(inputs[i]);
        }

        return resources;
    }

    /**
     * @param header A header to add to an outgoing or expected message
     * @param value  The value that should be assigned to this header
     */
    public static HeaderValue header(String header, Object value) {
        return new HeaderValue(header, value);
    }

    /**
     * @param headers A vararg list of headers which can be added to with the header(string,value) function
     */
    public static HeadersTestResource headers(HeaderValue... headers) {
        Map<String, Object> headersMap = new HashMap<>();
        for (HeaderValue header : headers) {
            headersMap.put(header.header, header.value);
        }

        return headers(headersMap);
    }

    /**
     * @param faultCode The fault code to use for the SOAP Fault
     * @param message   The message to use in the SOAP Fault
     */
    public static SoapFaultTestResource soapFault(QName faultCode, String message) {
        return new SoapFaultTestResource(faultCode, message);
    }

    /**
     * @param faultCode The fault code to use for the SOAP Fault
     * @param message   The message to use in the SOAP Fault
     * @param xmlDetail The XML detail to use in the SOAP Fault
     */
    public static SoapFaultTestResource soapFault(QName faultCode, String message, XmlTestResource xmlDetail) {
        return new SoapFaultTestResource(faultCode, message, xmlDetail);
    }

    /**
     * @return A validator for ensuring an exception occurs
     */
    public static ExceptionPredicate exception() {
        return new ExceptionPredicate();
    }

    /**
     * @param exception The exception we expect to validate against
     * @return A validator for ensuring an exception occurs
     */
    public static ExceptionPredicate exception(Class<? extends Exception> exception) {
        return new ExceptionPredicate(exception);
    }

    /**
     * @param exception The exception we expect to validate against
     * @param message   The message in the exception we expect to validate against
     * @return A validator for ensuring an exception occurs
     */
    public static ExceptionPredicate exception(Class<? extends Exception> exception, String message) {
        return new ExceptionPredicate(exception, message);
    }

    /**
     * @param path A path to a resource on the current classpath
     */
    public static URL classpath(String path) {
        URL resource = MorcTest.class.getResource(path);
        if (resource == null) throw new RuntimeException("The classpath resource could not be found: " + path);

        return resource;
    }

    /**
     * @param path A path to a file
     */
    public static File file(String path) {
        return new File(path);
    }

    /**
     * A convenience method for specifying matched input->output answers for expectations
     */
    @SuppressWarnings("unchecked")
    public static MatchedResponseProcessor matchedResponse(MatchedResponseProcessor.MatchedResponse... responses) {
        return new MatchedResponseProcessor(responses);
    }

    /**
     * A convenience method for specifying matched input validators to outputs
     */
    @SuppressWarnings("unchecked")
    public static MatchedResponseProcessor.MatchedResponse answer(Predicate predicate, TestResource resource) {
        try {
            return new MatchedResponseProcessor.MatchedResponse(predicate, new BodyProcessor(resource));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A convenience method for specifying matched input header validators to output headers
     */
    public static MatchedResponseProcessor.MatchedResponse headerAnswer(Predicate predicate, TestResource<Map<String, Object>> resource) {
        try {
            return new MatchedResponseProcessor.MatchedResponse(predicate, new HeadersProcessor(resource));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static MatchedResponseProcessor.MatchedResponse headerAnswer(HeadersTestResource expectedHeaders, TestResource<Map<String, Object>> resource) {
        return headerAnswer(new HeadersPredicate(expectedHeaders), resource);
    }

    /**
     * A way of paramaterizing resources so that values are updated according to Groovy GStrings
     *
     * @param template   A template of the string resource containing GString variables for substitution
     * @param dataSource A list of name=value pairs that will be used for var substitution. Each entry in the
     *                   list will result in another resource being returned
     */
    public static GroovyTemplateTestResource[] groovy(TestResource<String> template, List<Map<String, String>> dataSource) {
        return groovy(template, dataSource, GStringTemplateEngine.class);
    }

    /**
     * A way of paramaterizing resources so that values are updated according to Groovy GStrings
     *
     * @param template   A template of the string resource containing GString variables for substitution
     * @param dataSource A list of name=value pairs that will be used for var substitution. Each entry in the
     *                   list will result in another resource being returned
     */
    @SuppressWarnings("unchecked")
    public static GroovyTemplateTestResource[] groovy(String template, List<Map<String, String>> dataSource) {
        return groovy(new PlainTextTestResource(template), dataSource, GStringTemplateEngine.class);
    }

    /**
     * @param template       A template of the string resource containing template-appropriate variables for substitution
     * @param dataSource     A list of name=value pairs that will be used for var substitution. Each entry in the
     *                       list will result in another resource being returned
     * @param templateEngine The template engine, more can be found here: http://groovy.codehaus.org/Groovy+Templates
     */
    public static GroovyTemplateTestResource[] groovy(final TestResource<String> template, List<Map<String, String>> dataSource,
                                                      Class<? extends TemplateEngine> templateEngine) {
        GroovyTemplateTestResource[] results = new GroovyTemplateTestResource[dataSource.size()];
        try {
            final TemplateEngine engine = templateEngine.newInstance();

            int index = 0;
            for (Map<String, String> variables : dataSource) {
                results[index++] = new GroovyTemplateTestResource(engine, template, variables);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return results;
    }

    public static class VariablePair {
        private String name;
        private String value;

        public VariablePair(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    public static VariablePair var(String name, String value) {
        return new VariablePair(name, value);
    }

    /**
     * A way of paramaterizing resources so that values are updated according to Groovy GStrings
     *
     * @param template  A template of the string resource containing GString variables for substitution
     * @param variables A list of name=value pairs that will be used for var substitution. Each entry in the
     *                  list will result in another resource being returned
     */
    public static GroovyTemplateTestResource groovy(TestResource<String> template, VariablePair... variables) {
        return groovy(template, GStringTemplateEngine.class, variables);
    }

    /**
     * A way of paramaterizing resources so that values are updated according to Groovy GStrings
     *
     * @param template  A template of the string resource containing GString variables for substitution
     * @param variables A list of name=value pairs that will be used for var substitution. Each entry in the
     *                  list will result in another resource being returned
     */
    @SuppressWarnings("unchecked")
    public static GroovyTemplateTestResource groovy(String template, VariablePair... variables) {
        return groovy(new PlainTextTestResource(template), GStringTemplateEngine.class, variables);
    }

    /**
     * @param template       A template of the string resource containing template-appropriate variables for substitution
     * @param variables      A list of name=value pairs that will be used for var substitution. Each entry in the
     *                       list will result in another resource being returned
     * @param templateEngine The template engine, more can be found here: http://groovy.codehaus.org/Groovy+Templates
     */
    public static GroovyTemplateTestResource groovy(TestResource<String> template,
                                                    Class<? extends TemplateEngine> templateEngine, VariablePair... variables) {
        Map<String, String> map = new HashMap<>();
        for (VariablePair pair : variables) {
            map.put(pair.name, pair.value);
        }

        try {
            TemplateEngine engine = templateEngine.newInstance();
            return new GroovyTemplateTestResource(engine, template, map);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param templateUrl A URL for a Groovy template that will be evaluated as a plain text document
     * @param variables   A list of name=value pairs that will be used for var substitution. Each entry in the
     *                    list will result in another resource being returned
     */
    @SuppressWarnings("unchecked")
    public static GroovyTemplateTestResource groovy(URL templateUrl, VariablePair... variables) {
        return groovy(text(templateUrl), variables);
    }

    /**
     * @param templateUrl    A URL for a Groovy template that will be evaluated as a plain text document
     * @param variables      A list of name=value pairs that will be used for var substitution. Each entry in the
     *                       list will result in another resource being returned
     * @param templateEngine The template engine, more can be found here: http://groovy.codehaus.org/Groovy+Templates
     */
    @SuppressWarnings("unchecked")
    public static GroovyTemplateTestResource groovy(URL templateUrl,
                                                    Class<? extends TemplateEngine> templateEngine, VariablePair... variables) {
        return groovy(text(templateUrl), templateEngine, variables);
    }

    /**
     * @param groovyResources An array of Groovy templates which result in an XML document
     * @return A list of XmlTestResources that will be evaluated (at runtime) from the Groovy resources
     */
    @SuppressWarnings("unchecked")
    public static XmlRuntimeTestResource[] xml(GroovyTemplateTestResource... groovyResources) {
        XmlRuntimeTestResource[] resources = new XmlRuntimeTestResource[groovyResources.length];

        int i = 0;
        for (GroovyTemplateTestResource resource : groovyResources) {
            resources[i++] = new XmlRuntimeTestResource(resource);
        }

        return resources;
    }

    public static class XmlRuntimeTestResource implements TestResource<Document>, Predicate {

        private TestResource<String> resource;

        public XmlRuntimeTestResource(TestResource<String> resource) {
            this.resource = resource;
        }

        @Override
        public boolean matches(Exchange exchange) {
            return getResource().matches(exchange);
        }

        @Override
        public Document getValue() throws Exception {
            return getResource().getValue();
        }

        @Override
        public String toString() {
            return "XmlGroovyTemplateTestResource:" + getResource().toString();

        }

        private XmlTestResource getResource() {
            try {
                return new XmlTestResource(xmlUtilities.getXmlAsDocument(resource.getValue()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @param groovyResources A list of Groovy resources that will be evaluated as JSON documents
     */
    @SuppressWarnings("unchecked")
    public static JsonRuntimeTestResource[] json(GroovyTemplateTestResource... groovyResources) {
        JsonRuntimeTestResource[] resources = new JsonRuntimeTestResource[groovyResources.length];

        int i = 0;
        for (GroovyTemplateTestResource resource : groovyResources) {
            resources[i++] = new JsonRuntimeTestResource(resource);
        }

        return resources;
    }

    public static class JsonRuntimeTestResource implements TestResource<String>, Predicate {

        private TestResource<String> resource;

        public JsonRuntimeTestResource(TestResource<String> resource) {
            this.resource = resource;
        }

        @Override
        public boolean matches(Exchange exchange) {
            return getResource().matches(exchange);
        }

        @Override
        public String getValue() throws Exception {
            return getResource().getValue();
        }

        @Override
        public String toString() {
            return "JsonGroovyTemplateTestResource:" + getResource().toString();
        }

        private JsonTestResource getResource() {
            try {
                return new JsonTestResource(resource.getValue());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @param groovyResource A list of Groovy resources that will be evaluated as plain text
     */
    public static GroovyTemplateTestResource[] text(GroovyTemplateTestResource... groovyResource) {
        return groovyResource;
    }

    /**
     * @param urlpath An Ant-style path to a directory containing test resources for (expected) input and output
     * @return An array of InputStreams that can be used as test resources
     */
    public static InputStream[] dir(String urlpath) {
        List<URL> resourceUrls = new ArrayList<>();
        List<InputStream> resourceStreams = new ArrayList<>();

        try {
            Resource[] resources;
            resources = new PathMatchingResourcePatternResolver().getResources(urlpath);
            for (Resource resource : resources) {
                resourceUrls.add(resource.getURL());
            }

            //sort them alphabetically first
            Collections.sort(resourceUrls, new Comparator<URL>() {
                @Override
                public int compare(URL o1, URL o2) {
                    return o1.toString().compareTo(o2.toString());
                }
            });

            for (URL url : resourceUrls) {
                resourceStreams.add(url.openStream());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return resourceStreams.toArray(new InputStream[resourceStreams.size()]);
    }

    /**
     * This method can be used as a datasource for the groovy template
     *
     * @param csvResource A reference to a CSV file that contains var values. A header line sets the name of the variables
     * @return A list of variablename-value pairs
     */
    public static List<Map<String, String>> csv(TestResource<String> csvResource) {
        CSVReader reader;
        List<Map<String, String>> output = new ArrayList<>();
        String[] headers;

        try {
            reader = new CSVReader(new StringReader(csvResource.getValue()));

            headers = reader.readNext();

            if (new LinkedHashSet<>(Arrays.asList(headers)).size() != headers.length)
                throw new IllegalArgumentException("The headers for the csv " + csvResource + " are not unique");

            String[] nextLine;
            int line = 2;
            while ((nextLine = reader.readNext()) != null) {
                Map<String, String> variableMap = new HashMap<>();

                if (nextLine.length != headers.length)
                    throw new IllegalArgumentException("The CSV resource " + csvResource + " has a different " +
                            "number of headers and values for line " + line);

                for (int i = 0; i < nextLine.length; i++) {
                    variableMap.put(headers[i], nextLine[i]);
                }

                output.add(variableMap);
                line++;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return output;
    }

    /**
     * @param endpointUri The endpoint URI that a mock should listen to; should follow the Apache Camel URI format
     */
    public static AsyncMockDefinitionBuilder asyncExpectation(String endpointUri) {
        return new AsyncMockDefinitionBuilder(endpointUri);
    }

    /**
     * @param endpointUri The endpoint URI that a mock should listen to; should follow the Apache Camel URI format
     */
    public static HttpErrorMockDefinitionBuilder httpErrorExpectation(String endpointUri) {
        return new HttpErrorMockDefinitionBuilder(endpointUri);
    }

    /**
     * @param endpointUri The endpoint URI that the mock should listen to; should follow the Apache Camel URI format
     */
    public static SoapFaultMockDefinitionBuilder soapFaultExpectation(String endpointUri) {
        return new SoapFaultMockDefinitionBuilder(endpointUri);
    }

    /**
     * @param endpointUri The endpoint URI that a mock should listen to; should follow the Apache Camel URI format
     */
    public static ExceptionMockDefinitionBuilder exceptionExpectation(String endpointUri) {
        return new ExceptionMockDefinitionBuilder(endpointUri);
    }

    /**
     * @param endpointUri The endpoint URI that a mock should listen to; should follow the Apache Camel URI format
     * @param exception   The exception that should be instantiated and thrown as part of the expectation
     */
    public static ExceptionMockDefinitionBuilder exceptionExpectation(String endpointUri,
                                                                      final Class<? extends Exception> exception) {
        try {
            Constructor<? extends Exception> constructor = exception.getConstructor();
            return new ExceptionMockDefinitionBuilder(endpointUri).exception(constructor.newInstance());
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * @param endpointUri The endpoint URI that a mock should listen to; should follow the Apache Camel URI format
     * @param exception   The exception that should be instantiated and thrown as part of the expectation
     * @param message
     * @return
     */
    public static ExceptionMockDefinitionBuilder exceptionExpectation(String endpointUri,
                                                                      final Class<? extends Exception> exception,
                                                                      final String message) {
        try {
            Constructor<? extends Exception> constructor = exception.getConstructor(String.class);
            return new ExceptionMockDefinitionBuilder(endpointUri).exception(constructor.newInstance(message));
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        }
    }


    /**
     * @param endpointUri The endpoint URI that a mock should listen to; should follow the Apache Camel URI format
     */
    public static SyncMockDefinitionBuilder syncExpectation(String endpointUri) {
        return new SyncMockDefinitionBuilder(endpointUri);
    }

    /**
     * @param endpointUri The endpoint URI that a mock should listen to; should follow the Apache Camel URI format
     */
    public static UnreceivedMockDefinitionBuilder unreceivedExpectation(String endpointUri) {
        return new UnreceivedMockDefinitionBuilder(endpointUri);
    }

    public static class HeaderValue {
        private String header;
        private Object value;

        public HeaderValue(String header, Object value) {
            this.header = header;
            this.value = value;
        }
    }

    /**
     * @return A validator that ensures that the HTTP response body meets the expected response
     */
    public static HttpErrorPredicate httpExceptionResponse(Predicate predicate) {
        return new HttpErrorPredicate.Builder().responseBody(predicate).build();
    }

    /**
     * @return A validator that ensures that the HTTP response body meets the expected response
     */
    public static HttpErrorPredicate httpExceptionResponse(int statusCode, Predicate predicate) {
        return new HttpErrorPredicate.Builder().responseBody(predicate).statusCode(statusCode).build();
    }

    /**
     * @return A validation builder for setting http exception response values
     */
    public static HttpErrorPredicate.Builder httpExceptionResponse() {
        return new HttpErrorPredicate.Builder();
    }

    /**
     * @param statusCode The HTTP status code that is expected to be received back
     * @return A validator that ensures that the HTTP response meets the expected XML response body
     */
    public static HttpErrorPredicate httpExceptionResponse(int statusCode) {
        return new HttpErrorPredicate.Builder().statusCode(statusCode).build();
    }

    /**
     * A simple way for generating a QName for SOAP Faults
     */
    public static QName qname(String uri, String localName) {
        return new QName(uri, localName);
    }

    public static class NS {
        private String prefix;
        private String uri;

        public NS(String prefix, String uri) {
            this.prefix = prefix;
            this.uri = uri;
        }

        public String getPrefix() {
            return this.prefix;
        }

        public String getUri() {
            return this.uri;
        }
    }

    /**
     * @return A namespace designation for xpath evaluation of xml results
     */
    public static NS namespace(String prefix, String uri) {
        return new NS(prefix, uri);
    }

    /**
     * @param time The time in milliseconds to delay the processing of a message (either when publishing to a target or
     *             replying to a system)
     */
    public static Processor delay(final long time) {
        return new Processor() {
            @Override
            public void process(Exchange exchange) throws Exception {
                Thread.sleep(time);
            }
        };
    }

    /**
     * Returns an XPathBuilder that can be used as a Predicate to evaluate a request or response is as expected
     *
     * @param expression An XPath expression that evaluates the incoming exchange body to a boolean value
     * @param namespaces Namespace definitions used within the XPath expression
     */
    public static XPathBuilder xpath(String expression, NS... namespaces) {
        XPathBuilder builder = new XPathBuilder(expression);
        for (NS namespace : namespaces) {
            builder.namespace(namespace.getPrefix(), namespace.getUri());
        }
        return builder;
    }

    /**
     * @param expression A JSONPath Expression that evaluates to true or false
     * @return A predicate that evaluates the JSON Path expression
     */
    public static Predicate jsonpath(String expression) {
        return new JsonPathExpression(expression);
    }

    /**
     * @param expression A regular expression to evaluate against the message body
     */
    public static Predicate regex(String expression) {
        return new SimpleBuilder("${body} regex '" + expression + "'");
    }

    //this is used by JUnit to initialize each instance of this specification
    protected List<OrchestratedTestSpecification> getSpecifications() {
        configure();

        List<OrchestratedTestSpecification> specifications = new ArrayList<>();

        for (OrchestratedTestSpecification.OrchestratedTestSpecificationBuilderInit builder : specificationBuilders) {
            OrchestratedTestSpecification spec = builder.build();
            specifications.add(spec);
        }

        return specifications;
    }

    /**
     * A method to allow tests to be run from simple scripts without all the JUnit infrastructure
     *
     * @return The number of failed tests
     */
    public int run() {
        JoranConfigurator configurator = new JoranConfigurator();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            configurator.setContext(context);
            context.reset();
            configurator.doConfigure(MorcTestBuilder.classpath("/logback-morc.xml"));
        } catch (JoranException e) {
            throw new RuntimeException(e);
        }

        JUnitCore core = new JUnitCore();
        core.addListener(new TextListener(System.out));
        try {
            Result r = core.run(new MorcParameterized(this));
            return r.getFailureCount();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
