package life.genny.bridgecmd;

import org.bitsofinfo.hazelcast.discovery.docker.swarm.SwarmAddressPicker;
import org.bitsofinfo.hazelcast.discovery.docker.swarm.SystemPrintLogger;

import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.AddressPicker;
import com.hazelcast.instance.DefaultNodeContext;
import com.hazelcast.instance.HazelcastInstanceFactory;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.NodeContext;

import io.vertx.core.Future;
import io.vertx.rxjava.core.AbstractVerticle;

public class ServiceVerticle extends AbstractVerticle {

	@Override
	public void start() {
		setupCluster();

	}

	public void setupCluster() {
		Future<Void> startFuture = Future.future();
		createCluster().compose(v -> {
			startFuture.complete();
		}, startFuture);
	}

	public Future<Void> createCluster() {
		Future<Void> startFuture = Future.future();

		System.out.println("hazelcastInterface=[" + System.getProperty("hazelcastInterface") + "]");

		vertx.executeBlocking(future -> {
			Config conf = new ClasspathXmlConfig("hazelcast-genny.xml");
			System.out.println("Starting hazelcast DISCOVERY!");
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

	public static void main(String[] args) throws Exception {

	}
}
