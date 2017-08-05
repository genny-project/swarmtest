package life.genny.bridgecmd;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.bitsofinfo.hazelcast.discovery.docker.swarm.SwarmAddressPicker;
import org.bitsofinfo.hazelcast.discovery.docker.swarm.SystemPrintLogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.AddressPicker;
import com.hazelcast.instance.DefaultNodeContext;
import com.hazelcast.instance.HazelcastInstanceFactory;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.NodeContext;

import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.auth.oauth2.OAuth2FlowType;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeEventType;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.rxjava.core.AbstractVerticle;
import io.vertx.rxjava.core.eventbus.EventBus;
import io.vertx.rxjava.core.eventbus.Message;
import io.vertx.rxjava.core.eventbus.MessageProducer;
import io.vertx.rxjava.ext.auth.oauth2.AccessToken;
import io.vertx.rxjava.ext.auth.oauth2.OAuth2Auth;
import io.vertx.rxjava.ext.auth.oauth2.providers.KeycloakAuth;
import io.vertx.rxjava.ext.web.Router;
import io.vertx.rxjava.ext.web.RoutingContext;
import io.vertx.rxjava.ext.web.handler.sockjs.BridgeEvent;
import io.vertx.rxjava.ext.web.handler.sockjs.SockJSHandler;
import rx.Observable;

public class ServiceVerticle extends AbstractVerticle {

	private static final Logger logger = LoggerFactory.getLogger(ServiceVerticle.class);

	private EventBus eventBus = null;
	MessageProducer<JsonObject> msgToFrontEnd;
	Observable<Message<Object>> events;
	Observable<Message<Object>> cmds;
	Observable<Message<Object>> data;

	JsonObject keycloakJson;
	AccessToken tokenAccessed;

	private OAuth2Auth oauth2;
	String token ;

	Gson gson = new GsonBuilder().registerTypeAdapter(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
		@Override
		public LocalDateTime deserialize(JsonElement json, Type type,
				JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
			return ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString()).toLocalDateTime();
		}

		public JsonElement serialize(LocalDateTime date, Type typeOfSrc, JsonSerializationContext context) {
			return new JsonPrimitive(date.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)); // "yyyy-mm-dd"
		}
	}).create();
	
	@Override
	public void start() {
		setupCluster();

	}

	public void setupCluster() {
		Future<Void> startFuture = Future.future();
		createCluster().compose(v -> {
			eventListeners();
			registerLocalAddresses();
			eventsInOutFromCluster();

			startFuture.complete();
		}, startFuture);
	}

	public Future<Void> createCluster() {
		Future<Void> startFuture = Future.future();


		vertx.executeBlocking(future -> {
			Config conf = new ClasspathXmlConfig("hazelcast-genny.xml");
			System.out.println("Starting hazelcast DISCOVERY!!!!!");
			NodeContext nodeContext = new DefaultNodeContext() {
				@Override
				public AddressPicker createAddressPicker(Node node) {
					return new SwarmAddressPicker(new SystemPrintLogger());
				}
			};

			HazelcastInstance hazelcastInstance = HazelcastInstanceFactory.newHazelcastInstance(conf,
					"hazelcast-genny", nodeContext);
			System.out.println("Done hazelcast DISCOVERY");
		}, res -> {
			if (res.succeeded()) {

			}
		});

		return startFuture;
	}

	public Future<Void> runRouters() {
		System.out.println("Setting up routes");
		Future<Void> fut = Future.future();
		Router router = Router.router(vertx);
	//	router.route("/frontend/*").handler(this::checkToken);
		router.route("/frontend/*").handler(eventBusHandler());
		router.route(HttpMethod.POST, "/api/events").handler(this::apiHandler);
		router.route(HttpMethod.POST, "/api/service").handler(this::apiServiceHandler);

		vertx.createHttpServer().requestHandler(router::accept).listen(8081);
		fut.complete();
		return fut;
	}
	public Future<Void> securityProviderReader() {
		System.out.println("Security Provider Reader");
		Future<Void> fut1 = Future.future();
		vertx.fileSystem().readFile("realm/keycloak.json", d -> {
			if (!d.failed()) {
				keycloakJson = d.result().toJsonObject();
				fut1.complete();
				System.out.println(keycloakJson);
			} else {
				System.err.println("Error reading keycloak.json file!");
			}
		});
		return fut1;
	}

	public void createAuth2() {
		logger.info("Creating OAUTH2");
		Future<Void> startFuture = Future.future();
		securityProviderReader().compose(v -> {
			oauth2 = KeycloakAuth.create(vertx, OAuth2FlowType.PASSWORD, keycloakJson);
			startFuture.completer();
		}, startFuture);
	}

	public void checkToken(RoutingContext routingContext) {
		// String authToken = routingContext.request().getHeader("Authorization");
		// for (String header : routingContext.request().headers().names()) {
		// System.out.println("headers="+header+":"+routingContext.request().headers().get(header));
		// }
		if (routingContext.get("token") != null) {
			System.out.println("TOKEN NOT NULL IN CHECK TOKEN");
			routingContext.next();
		} else {
			System.out.println("TOKEN IS  NULL IN CHECK TOKEN");

		}

		String token = routingContext.request().getParam("token");
		if ((token == null)||(token.isEmpty())) {
			token = this.token; // cheat to get around lacl of token from alyson
		} else {
			this.token = token;
		}
		System.out.println("Token to be checked=" + token.substring(0, 10) + "..." + token.substring(token.length() - 10));
		oauth2.introspectToken(token, res -> {
			if (res.succeeded()) {
				// token is valid!
				tokenAccessed = res.result();
				System.out.println("TokenAccessed:" + tokenAccessed.principal().toString());
				
				System.out.println("PASSED TOKEN SUCCEED " + tokenAccessed.principal().toString().substring(0, 10) + "..." + tokenAccessed.principal().toString().substring(tokenAccessed.principal().toString().length() - 10));

				routingContext.put("token", "true");
				// tokenAccessed.principal()
				String username = tokenAccessed.principal().getString("preferred_username");
				System.out.println("Username="+username);
				routingContext.next();
			} else {
				System.err.println(
						"PASSED TOKEN FAILED ");
				routingContext.response().setStatusCode(403).end();
			}
		});
		// routingContext.next();
		// routingContext.response().setStatusCode(403).end();
	}

	public void eventListeners() {
		events = eventBus.consumer("events").toObservable();
		cmds = eventBus.consumer("cmds").toObservable();
		data = eventBus.consumer("data").toObservable();
	}

	public void registerLocalAddresses() {
		msgToFrontEnd = vertx.eventBus().publisher("address.outbound");
	}
	/*
	 * Write any cmds or data out to the frontend
	 */
	public void eventsInOutFromCluster() {
		cmds.subscribe(arg -> {
			String incomingCmd = arg.body().toString();
			logger.info(incomingCmd);
			if (!incomingCmd.contains("<body>Unauthorized</body>")) {
				JsonObject json = new JsonObject(incomingCmd);     //Buffer.buffer(arg.toString().toString()).toJsonObject();
				msgToFrontEnd.write(json);
			} else {
				logger.error("Cmd with Unauthorised data recieved");
			}
		});
		data.subscribe(arg -> {
			String incomingData = arg.body().toString();
			logger.info(incomingData);
			JsonObject json = new JsonObject(incomingData);     //Buffer.buffer(arg.toString().toString()).toJsonObject();
			msgToFrontEnd.write(json);
//			msgToFrontEnd.write(Buffer.buffer(arg.body().toString()).toJsonObject());
		});
	}

	private SockJSHandler eventBusHandler() {
		// BridgeOptions options = new BridgeOptions()
		// .addOutboundPermitted(new
		// PermittedOptions().setAddressRegex("auction\\.[0-9]+"));
		PermittedOptions inboundPermitted1 = new PermittedOptions().setAddress("address.inbound");
		PermittedOptions outboundPermitted2 = new PermittedOptions().setAddressRegex("address.outbound");
		BridgeOptions options = new BridgeOptions();
		options.setMaxAddressLength(10000);
		options.addInboundPermitted(inboundPermitted1);
		options.addOutboundPermitted(outboundPermitted2);

		SockJSHandler sockJSHandler = SockJSHandler.create(vertx);
		return sockJSHandler.bridge(options, this::bridgeHandler);

	}

	public void bridgeHandler(BridgeEvent bridgeEvent) {
		if (bridgeEvent.type() == BridgeEventType.SOCKET_CREATED) {
			logger.info("A websocket was created " + bridgeEvent.socket().remoteAddress());
		} else	if (bridgeEvent.type() == BridgeEventType.PUBLISH || bridgeEvent.type() == BridgeEventType.SEND) {
			JsonObject rawMessage = bridgeEvent.getRawMessage().getJsonObject("body");
			rawMessage = rawMessage.getJsonObject("data"); 
			logger.info("Incoming Frontend Event :" + rawMessage);
			eventBus.publish("events", rawMessage);
		}


		bridgeEvent.complete(true);
	}

	public void apiHandler(RoutingContext routingContext) {

		final String token = routingContext.request().getParam("token");
		//
		// Router router = Router.router(vertx);
		// handler = new EventHandler(BodyHandler.create());
		// //router.route().handler(BodyHandler.create());
		// router.route().blockingHandler(handler);
		// router.route().consumes("application/json");
		// router.route().produces("application/json");

		// router.route("/auctions/:id").handler(handler::initAuctionInSharedData);
		// router.get("/event/:id").handler(handler::handleGetAuction);
		routingContext.request().bodyHandler(body -> {
			//
			JsonObject j = body.toJsonObject();
			j.put("token", token);
			this.token = token;
			logger.info(j);
			if (j.getString("msg_type").equals("EVT_MSG")) {
			// System.out.println("Send through to RulesService");
			// // rawMessage.put("token", tokenAccessed.principal().toString());
				eventBus.publish("events", j);
			} else {
			//
			// try {
			// System.out.println("Send through to Frontend");
			// // System.out.println(body.toJsonArray());
			// msgToFrontEnd.write(j);
			// } catch (DecodeException e) {
			// System.out.println(j);
			// msgToFrontEnd.write(j);
			// }
			 }
		});
		routingContext.response().end();
	}

	public void apiServiceHandler(RoutingContext routingContext) {

		final String token = routingContext.request().getParam("token");
		routingContext.request().bodyHandler(body -> {
			//
			JsonObject j = body.toJsonObject();
			j.put("token",token);  //TODO, create Keycloak Service Token
			logger.info("KEYCLOAK:"+j);
			if (j.getString("msg_type").equals("EVT_MSG")) {
				eventBus.publish("events", j);
			} else {
			 }
		});
		routingContext.response().end();
	}	
	
	private ErrorHandler errorHandler() {
		return ErrorHandler.create(true);
	}

	private StaticHandler staticHandler() {
		return StaticHandler.create().setCachingEnabled(false);
	}
}
