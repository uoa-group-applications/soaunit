package nz.ac.auckland.integration.testing;

import nz.ac.auckland.integration.testing.endpointoverride.CxfEndpointOverride;
import nz.ac.auckland.integration.testing.endpointoverride.EndpointOverride;
import nz.ac.auckland.integration.testing.expectation.MockExpectation;
import nz.ac.auckland.integration.testing.specification.OrchestratedTestSpecification;
import org.apache.camel.*;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.cxf.CxfComponent;
import org.apache.camel.component.cxf.CxfEndpoint;
import org.apache.camel.component.cxf.DataFormat;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.test.spring.CamelSpringTestSupport;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.AbstractXmlApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This carries out the actual testing of the orchestrated specification specification - ensuring
 * ordering of the received exchanges is as expected
 * <p/>
 * This will be extended for actual tests and will use JUnit Parameterized to add parameters at runtime.
 *
 * @author David MacDonald <d.macdonald@auckland.ac.nz>
 */
public class OrchestratedTest extends CamelSpringTestSupport {

    private String[] springContextPaths;
    private final OrchestratedTestSpecification specification;
    private Logger logger = LoggerFactory.getLogger(OrchestratedTest.class);
    private PropertiesComponent properties = null;
    private Collection<EndpointOverride> endpointOverrides = new ArrayList<>();

    /**
     * @param specification A test specification that this test runs
     */
    public OrchestratedTest(OrchestratedTestSpecification specification) {
        this(new String[]{}, specification);
    }

    /**
     * @param propertiesPath a classpath path for a properties file
     * @param specification  A test specification that this test runs
     */
    public OrchestratedTest(String propertiesPath, OrchestratedTestSpecification specification) {
        this(specification);
        properties = new PropertiesComponent();
        properties.setLocation(propertiesPath);
    }

    /**
     * @param springContextPaths An array of file paths to Spring Contexts used to set up Camel and any dependencies
     * @param specification      A test specification that this test runs
     */
    public OrchestratedTest(String[] springContextPaths, OrchestratedTestSpecification specification) {
        this.springContextPaths = springContextPaths;
        this.specification = specification;

        //we want CXF to be in PAYLOAD mode rather than POJO
        endpointOverrides.add(new CxfEndpointOverride());
    }

    protected AbstractXmlApplicationContext createApplicationContext() {
        return new ClassPathXmlApplicationContext(springContextPaths);
    }

    @Override
    protected CamelContext createCamelContext() throws Exception {
        CamelContext context = super.createCamelContext();
        if (properties != null) context.addComponent("properties", properties);

        return context;
    }

    /**
     * Override (and super call it) to modify the Camel endpoint for sending
     * and receiving messages
     * @param endpoint the current endpoint we are able to create a route for, or send a message to
     */
    protected void overrideEndpoint(Endpoint endpoint) {
        for (EndpointOverride override : endpointOverrides) {
            override.overrideEndpoint(endpoint);
        }
    }

    /**
     * @return The list of classes that modify the Camel endpoints when *sending* a message
     */
    public Collection<EndpointOverride> getEndpointOverrides() {
        return endpointOverrides;
    }

    /**
     * @param endpointOverrides a list of endpoint overrides that will modify the default endpoint string when
     *                          *sending* a message
     */
    public void setEndpointOverrides(Collection<EndpointOverride> endpointOverrides) {
        this.endpointOverrides = endpointOverrides;
    }

    @Test
    public void runOrchestratedTest() throws Exception {

        logger.info("Starting the test for specification: {}", specification.getDescription());

        //Using a UUID as I don't want exchanges sitting on a previous mock mucking up the tests
        final MockEndpoint mockEndpoint = context.getEndpoint("mock:" + UUID.randomUUID(), MockEndpoint.class);
        logger.debug("Obtaining new mock: {}", mockEndpoint.getEndpointUri());

        final Map<Endpoint, Queue<MockExpectation>> mockEndpointExpectations = new HashMap<>();
        final Map<String, List<MockExpectation>> unorderedEndpointExpectations = new HashMap<>();
        final Queue<MockExpectation> indexedExpectations = new PriorityQueue<>(11, new Comparator<MockExpectation>() {
            @Override
            public int compare(MockExpectation expectation1, MockExpectation expectation2) {
                return expectation1.getReceivedAt() - expectation2.getReceivedAt();
            }
        });
        final List<MockExpectation> unorderedExpectations = new ArrayList<>();

        int expectedMessageCount = 0;

        //put all of the expectations for each endpoint in an ordered queue
        for (MockExpectation expectation : specification.getMockExpectations()) {
            Endpoint fromEndpoint = context.getEndpoint(expectation.getEndpointUri());
            overrideEndpoint(fromEndpoint);
            logger.trace("Preparing mock expectation: {}", expectation.getName());

            if (!mockEndpointExpectations.containsKey(fromEndpoint)) {
                //we want a concurrent queue because it is possible that an async request will access
                //values simultaneously
                ConcurrentLinkedQueue<MockExpectation> queue = new ConcurrentLinkedQueue<>();
                mockEndpointExpectations.put(fromEndpoint, queue);
                logger.trace("Created new mock expectations queue for: {}", fromEndpoint.getEndpointUri());
            }

            //if there's more than one we expect them all to happen one after another; UnreceivedMockExpectation
            //will have nothing added (as expected)
            Queue<MockExpectation> expectationQueue = mockEndpointExpectations.get(fromEndpoint);

            /*
                Only setup these additional waits if we don't expect any messages to be sent to the endpoint, and also
                if the messages are expected to arrive out of order (which typically means that an asynchronous messaging
                system is in use and hence we're not sure when they'll arrive).
            */
            if (expectation.getExpectedMessageCount() == 0 ||
                    expectation.getOrderingType() != MockExpectation.OrderingType.TOTAL) {
                mockEndpoint.setSleepForEmptyTest(specification.getSleepForTestCompletion());
                mockEndpoint.setAssertPeriod(specification.getSleepForTestCompletion());
                mockEndpoint.setResultWaitTime(specification.getSleepForTestCompletion());
            }

            for (int i = 0; i < expectation.getExpectedMessageCount(); i++) {
                expectationQueue.add(expectation);
                if (expectation.getOrderingType() == MockExpectation.OrderingType.NONE)
                    unorderedExpectations.add(expectation);

                indexedExpectations.add(expectation);

                //these are needed later to evaluate out-of-order delivery
                if (!expectation.isEndpointOrdered()) {
                    if (!unorderedEndpointExpectations.containsKey(expectation.getEndpointUri()))
                        unorderedEndpointExpectations.put(expectation.getEndpointUri(), new ArrayList<MockExpectation>());

                    List<MockExpectation> endpointExpectations = unorderedEndpointExpectations.get(expectation.getEndpointUri());
                    endpointExpectations.add(expectation);
                }

                expectedMessageCount++;
            }

            logger.trace("Added: {} message(s) to the expectation queue for endpoint: {}",
                    expectation.getExpectedMessageCount(),
                    fromEndpoint.getEndpointUri());
        }

        //start dehydrating these queues when something is received from the mock endpoint
        //to a particular endpoint
        mockEndpoint.whenAnyExchangeReceived(new Processor() {
            @Override
            public void process(Exchange exchange) throws Exception {
                Queue<MockExpectation> expectations = mockEndpointExpectations.get(exchange.getFromEndpoint());
                if (expectations == null)
                    throw new IllegalStateException("The endpoint URI has no expectations: " + exchange.getFromEndpoint());
                MockExpectation expectation = expectations.poll();

                logger.trace("An exchange has been received from the endpoint: {} {}",
                        exchange.getFromEndpoint().getEndpointUri(), exchange.getIn().getBody(String.class));

                if (expectation == null) {
                    //this will be caught by the mock (an additional message will be received)
                    logger.debug("An exchange has been received from the endpoint: {} however no such expectation has been provided"
                            , exchange.getFromEndpoint());
                    return;
                }

                expectation.handleReceivedExchange(exchange);
                logger.debug("An exchange: {} has been received from the endpoint: {} and processed by the expectation: {}",
                        new String[]{exchange.getExchangeId(), exchange.getFromEndpoint().getEndpointUri(), expectation.getName()});
            }
        });

        logger.debug("The expected message count total for the mock is: {}", expectedMessageCount);
        mockEndpoint.expectedMessageCount(expectedMessageCount);

        //set up the endpoints that send everything through to a mock
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                for (Endpoint endpoint : mockEndpointExpectations.keySet()) {
                    from(endpoint)
                            .log(LoggingLevel.DEBUG, "An exchange has been received from the endpoint: " + endpoint.getEndpointUri())
                            .convertBodyTo(String.class)
                            .to(mockEndpoint);

                    logger.debug("Added a new route from: {} to mock: {}", endpoint.getEndpointUri(),
                            mockEndpoint.getEndpointUri());
                }
            }
        });

        //now set up the expectations of what we expect to receive, and in what order
        mockEndpoint.expects(new Runnable() {
            @Override
            public void run() {

                //we only require order of async messages by endpoint
                Map<String, LinkedList<MockExpectation>> partiallyOrderedExpectations = new HashMap<>();

                //copy the list because we are going to iterate over it, and may do so again for re-assertion
                Queue<MockExpectation> orderedExpectationsCopy = new LinkedList<>(Arrays.asList(indexedExpectations.toArray(new MockExpectation[indexedExpectations.size()])));
                List<MockExpectation> unorderedExpectationsCopy = new ArrayList<>(unorderedExpectations);
                Map<String, List<MockExpectation>> unorderedEndpointExpectationsCopy = new HashMap<>();
                for (String key : unorderedEndpointExpectations.keySet()) {
                    unorderedEndpointExpectationsCopy.put(key, new ArrayList<>(unorderedEndpointExpectations.get(key)));
                }

                int currentOrderedExpectationsOffset = 0;

                //we can operate under the assumption that the size of exchanges and the
                //size of the totalOrderedExpectations is the same
                for (int i = 0; i < mockEndpoint.getExchanges().size(); i++) {
                    Exchange currentExchange = mockEndpoint.getExchanges().get(i);
                    Endpoint fromEndpoint = currentExchange.getFromEndpoint();

                    logger.trace("Processing exchange: {} from endpoint: {}", currentExchange.getExchangeId(),
                            fromEndpoint.getEndpointUri());

                    //first check if we have a match against any of the unordered expectations
                    if (checkMockExpectationListContainsExchangeMatch(currentExchange, unorderedExpectationsCopy))
                        continue;

                    //if the exchange is at the front of a queue we've already seen then remove it
                    if (partiallyOrderedExpectations.containsKey(fromEndpoint.getEndpointUri()) &&
                            testPartiallyOrderedExpectationEndpointMatch(currentExchange,
                                    partiallyOrderedExpectations.get(fromEndpoint.getEndpointUri()), currentOrderedExpectationsOffset))
                        continue;

                    while (!orderedExpectationsCopy.isEmpty()) {

                        int currentOffset = currentOrderedExpectationsOffset++;

                        MockExpectation expected = orderedExpectationsCopy.poll();

                        logger.debug("Ordered Expectation Index: {}, Comparing Exchange: {} with expectation: {}",
                                new String[]{Integer.toString(currentOffset),
                                        currentExchange.getExchangeId(), expected.getName()});

                        //we've already validated all of these - they are only in the list to understand total order
                        if (expected.getOrderingType() == MockExpectation.OrderingType.NONE) continue;

                        boolean comparisonResult = expected.checkValid(currentExchange, currentOffset);

                        if (comparisonResult) {
                            logger.debug("Exchange: {} from endpoint: {} has met the expectation: {}",
                                    new String[]{currentExchange.getExchangeId(), fromEndpoint.getEndpointUri(), expected.getName()});

                            //we need to remove this so it can't be used again in the future
                            if (unorderedEndpointExpectationsCopy.containsKey(expected.getEndpointUri()))
                                checkMockExpectationListContainsExchangeMatch(currentExchange, unorderedEndpointExpectationsCopy.get(expected.getEndpointUri()));

                            break;
                        }

                        String messageBody = currentExchange.getIn().getBody(String.class);
                        AssertionError orderError = new AssertionError("The expectation: " + expected.getName() + " has not been encountered" +
                                " in the correct order; the message: " + (messageBody.length() >= 20 ? messageBody.substring(0, 20) + "..." : messageBody) +
                                " was received first");

                        //check if the exchange matches any of the unordered endpoints
                        if (!expected.isEndpointOrdered() &&
                                currentExchange.getFromEndpoint().getEndpointUri().equals(expected.getEndpointUri())) {

                            if (checkMockExpectationListContainsExchangeMatch(currentExchange,
                                    unorderedEndpointExpectationsCopy.get(expected.getEndpointUri()))) break;
                            else
                                throw orderError;
                        }

                        if (expected.getOrderingType() == MockExpectation.OrderingType.TOTAL ||
                                (expected.isEndpointOrdered() && currentExchange.getFromEndpoint().getEndpointUri().equals(expected.getEndpointUri())))
                            throw orderError;

                        //it might pop up in the future so we need to add it
                        if (!partiallyOrderedExpectations.containsKey(expected.getEndpointUri())) {
                            LinkedList<MockExpectation> mockList = new LinkedList<>();
                            partiallyOrderedExpectations.put(expected.getEndpointUri(), mockList);
                        }
                        Queue<MockExpectation> partiallyOrderedMockList = partiallyOrderedExpectations.get(expected.getEndpointUri());

                        partiallyOrderedMockList.add(expected);
                        logger.trace("The expectation: {} is partially ordered and may be encountered in the future - " +
                                " adding it to a queue for this endpoint", expected.getName());

                    }
                }

                for (String partiallyOrderedEndpoint : partiallyOrderedExpectations.keySet()) {
                    if (partiallyOrderedExpectations.get(partiallyOrderedEndpoint).size() > 0) {
                        MockExpectation expectation = partiallyOrderedExpectations.get(partiallyOrderedEndpoint).poll();
                        throw new AssertionError("The expectation: " + expectation.getName() +
                                " was not met for partially ordered criteria to the endpoint: " + partiallyOrderedEndpoint);
                    }
                }

                if (unorderedExpectationsCopy.size() > 0) {
                    MockExpectation expectation = unorderedExpectationsCopy.get(0);
                    throw new AssertionError("The expectation: " + expectation.getName() + " was not satisfied");
                }
            }
        });

        //send the request through to the target endpoint, ensure it gets sent and there's a valid response
        boolean testSendState = specification.sendInput(context.createProducerTemplate());

        if (!testSendState) throw new AssertionError("The message could not be sent to the target destination: "
                + specification.getTargetServiceUri() + ", or the response was invalid");

        logger.trace("Starting to check if the mock endpoint assert is satisfied");
        mockEndpoint.assertIsSatisfied();
        logger.trace("The mock endpoint is satisfied");

        //free any memory of all of the exchanges
        mockEndpoint.reset();

        logger.info("Successfully completed the specification: " + specification.getDescription());
    }

    private boolean checkMockExpectationListContainsExchangeMatch(Exchange exchange, List<MockExpectation> mockExpectations) {
        for (MockExpectation expectation : mockExpectations) {
            //we're using it's own received at index because we know it's valid
            if (expectation.checkValid(exchange, expectation.getReceivedAt())) {
                mockExpectations.remove(expectation);
                //we've matched up this exchange... move onto the next one
                logger.debug("Exchange: {} from endpoint: {} has met the endpoint expectation: {}",
                        new String[]{exchange.getExchangeId(), expectation.getEndpointUri(), expectation.getName()});
                return true;
            }
        }

        return false;
    }

    private boolean testPartiallyOrderedExpectationEndpointMatch(Exchange exchange, List<MockExpectation> mockExpectations, int expectationIndex) {
        for (MockExpectation partialMock : mockExpectations) {
            if (partialMock.checkValid(exchange, expectationIndex)) {
                mockExpectations.remove(partialMock);
                logger.debug("Exchange: {} has met the partially ordered expectation for: {}",
                        exchange.getExchangeId(), partialMock.getName());
                return true;
            }

            //we only look at the first element for totally ordered endpoints and
            //we've gone through all of the expectations and none match it
            if (partialMock.isEndpointOrdered())
                throw new AssertionError("The expectation: " + partialMock.getName()
                        + " for the exchange: " + exchange.getExchangeId() + " from endpoint: " +
                        exchange.getFromEndpoint().getEndpointUri() + " was not satisfied");
        }

        return false;
    }
}

