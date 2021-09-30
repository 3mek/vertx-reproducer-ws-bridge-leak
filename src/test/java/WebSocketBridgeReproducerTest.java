import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.sockjs.BridgeEvent;
import io.vertx.ext.web.handler.sockjs.SockJSBridgeOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.CoreMatchers.is;

/**
 * @author Szymon Glombiowski
 */

@RunWith(VertxUnitRunner.class)
public class WebSocketBridgeReproducerTest extends VertxTestBase {
    public static final String EVENTBUS_ADDRESS = "addr1";
    public static final String EVENTBUS_REGISTER_MESSAGE = "{\"type\":\"register\",\"address\":\"" + EVENTBUS_ADDRESS + "\",\"headers\":{\"Accept\":\"application/json\"}}";
    public static final String EVENTBUS_UNREGISTER_MESSAGE = "{\"type\":\"unregister\",\"address\":\"" + EVENTBUS_ADDRESS + "\",\"headers\":{\"Accept\":\"application/json\"}}";
    public static final String WSS_PATH = "/wss/";
    public static final String WEBSOCKET_PATH = WSS_PATH + "websocket";
    private static final Logger log = LoggerFactory.getLogger(WebSocketBridgeReproducerTest.class);
    public static final int PORT = 8080;
    public static final String LOCALHOST = "localhost";
    private static int counter = 0;
    Vertx vertx;
    HttpServer server;

    @Before
    public void before(TestContext context) {

        vertx = Vertx.vertx();

        vertx.exceptionHandler(context.exceptionHandler());
        server = vertx.createHttpServer();

        Router router = Router.router(vertx);
        router.route().handler(LoggerHandler.create());
        router.route("/").handler(event -> event.request().response().end("test"));

        Router sockJSRouter = createEventBusRouter();
        router.mountSubRouter(WSS_PATH, sockJSRouter);

        server.requestHandler(router);
        server.listen(PORT, context.asyncAssertSuccess());

        vertx.setPeriodic(1000, id -> {
            log.info("server sending number: " + ++counter);
            vertx.eventBus().send(EVENTBUS_ADDRESS, counter);
        });
    }

    @After
    public void after(TestContext context) {
        vertx.close(context.asyncAssertSuccess());
    }

    @Test
    public void testHTTPCall(TestContext context) {
        // Send a request and get a response
        HttpClient client = vertx.createHttpClient();
        client.request(HttpMethod.GET, PORT, LOCALHOST, "/")
                .compose(req -> req
                        .send()
                        .compose(HttpClientResponse::body))
                .onComplete(context.asyncAssertSuccess(body -> {
                    assertThat(body.toString(), is("test"));
                    client.close();
                }));
    }

    @Test
    public void testWsGETCall(TestContext context) {
        // Send a request and get a response
        HttpClient client = vertx.createHttpClient();
        client.request(HttpMethod.GET, PORT, LOCALHOST, "/wss/")
                .compose(req -> req
                        .send()
                        .compose(HttpClientResponse::body))
                .onComplete(context.asyncAssertSuccess(body -> {
                    assertThat(body.toString(), is("Welcome to SockJS!\n"));
                    client.close();
                }));
    }

    @Test
    public void testEventBusBridgeLeakingConsumers(TestContext context) {
        // initial connection - double registration and unregistration
        HttpClient client = vertx.createHttpClient();
        client.webSocket(PORT, LOCALHOST, WEBSOCKET_PATH, onSuccess(ws -> {
            // those actions will cause leak - a consumer still registered
            ws.writeTextMessage(EVENTBUS_REGISTER_MESSAGE);
            delay();
            ws.writeTextMessage(EVENTBUS_REGISTER_MESSAGE);
            delay();
            ws.writeTextMessage(EVENTBUS_UNREGISTER_MESSAGE);
            delay();
            // this does not do anything actually
            ws.writeTextMessage(EVENTBUS_UNREGISTER_MESSAGE);
            ws.handler(buff -> {
                log.info("websocket client 1 received raw message: " + buff.toString("UTF-8"));
                ws.close();
            });

        }));

        final int[] counter = {-1};
        HttpClient client2 = vertx.createHttpClient();
        client2.webSocket(PORT, LOCALHOST, WEBSOCKET_PATH, onSuccess(ws -> {
            ws.writeTextMessage(EVENTBUS_REGISTER_MESSAGE);
            // this client will only receive every other message
            delay();
            ws.handler(buff -> {
                log.debug("websocket client 2 received raw message: " + buff.toString("UTF-8"));
                JsonObject jsonObject = new JsonObject(buff.toString("UTF-8"));
                int number = jsonObject.getInteger("body");
                log.info("websocket client 2 received number: " + number);
                // initialize - some messages might have already been handled by 1st client
                if (counter[0] == -1) {
                    counter[0] = number;
                } else {
                    ++counter[0];
                }
                // new number in message should always be increased by 1
                assertEquals("Message was lost, next id not matching.", counter[0], number);
            });

        }));

        await();
    }

    private void delay() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    private Router createEventBusRouter() {
        PermittedOptions permittedOptions = new PermittedOptions()
                .setAddress(EVENTBUS_ADDRESS);

        SockJSBridgeOptions bridgeOptions = new SockJSBridgeOptions()
                .addInboundPermitted(permittedOptions)
                .addOutboundPermitted(permittedOptions)
                .setPingTimeout(60000);  // should not interfere with test
        return SockJSHandler.create(vertx).bridge(bridgeOptions, new TestBridgeEventHandler());
    }


    class TestBridgeEventHandler implements Handler<BridgeEvent> {
        @Override
        public void handle(BridgeEvent event) {
            JsonObject rawMessage = event.getRawMessage();
            log.debug("Bridge event type=" + event.type() + ", raw message=" + encode(rawMessage));
            event.complete(true);
        }

        private String encode(JsonObject rawMessage) {
            return rawMessage != null ? rawMessage.encode() : "null";
        }

    }

}