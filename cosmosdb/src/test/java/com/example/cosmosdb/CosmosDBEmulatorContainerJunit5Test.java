package com.example.cosmosdb;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.SneakyThrows;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Properties;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.CosmosDBEmulatorContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.azure.cosmos.CosmosAsyncClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = CosmosDBEmulatorContainerJunit5Test.Initializer.class)
@Testcontainers
public class CosmosDBEmulatorContainerJunit5Test {
	static final Logger log = LoggerFactory.getLogger(CosmosDBEmulatorContainerJunit5Test.class);

	static Properties originalSystemProperties;

	@Autowired
	CosmosAsyncClient cosmosAsyncClient;

	@Autowired
	ReactiveAssetRepository reactiveAssetRepository;

	// The CosmosDBEmulatorContainer has port 8081 hard coded
	// https://mcr.microsoft.com/v2/cosmosdb/linux/azure-cosmos-emulator/tags/list
	public static CosmosDBEmulatorContainer emulator = new CosmosDBEmulatorContainer(
			DockerImageName.parse("mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest")
	)
			.withEnv("AZURE_COSMOS_EMULATOR_PARTITION_COUNT", "2")
			.withEnv("AZURE_COSMOS_EMULATOR_ENABLE_DATA_PERSISTENCE", "true")
			// Without this line, it doesn't work
			.withEnv("AZURE_COSMOS_EMULATOR_IP_ADDRESS_OVERRIDE", "127.0.0.1")
			.withCreateContainerCmdModifier(
					e -> e.getHostConfig().withPortBindings(
							new PortBinding(Ports.Binding.bindPort(8081), new ExposedPort(8081))
					));

	static {
		emulator.start();
		System.err.println("Emulator started");
	}

	static class Initializer
			implements ApplicationContextInitializer<ConfigurableApplicationContext> {
		@SneakyThrows
		public void initialize(ConfigurableApplicationContext configurableApplicationContext) {
			TemporaryFolder tempFolder = TemporaryFolder.builder().assureDeletion().build();
			tempFolder.create();
			Path keyStoreFile = tempFolder.newFile("azure-cosmos-emulator.keystore").toPath();
			KeyStore keyStore = emulator.buildNewKeyStore();
			keyStore.store(new FileOutputStream(keyStoreFile.toFile()), emulator.getEmulatorKey().toCharArray());

			System.setProperty("javax.net.ssl.trustStore", keyStoreFile.toString());
			System.setProperty("javax.net.ssl.trustStorePassword", emulator.getEmulatorKey());
			System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

			var containerIpAddress = emulator.getContainerIpAddress();
			var endpoint = emulator.getEmulatorEndpoint();
			var key = emulator.getEmulatorKey();

			TestPropertyValues.of(
					"azure.cosmos.uri=" + endpoint,
					"azure.cosmos.key=" + key,
					"azure.cosmos.validate-uri=false",
					"connection-mode=direct",
					"azure.cosmos.database=" + "Assets"
			).applyTo(configurableApplicationContext.getEnvironment());
		}
	}

	@AfterEach
	public void teardown() {
		cosmosAsyncClient.getDatabase("Assets").delete().block();
	}

	@AfterAll
	public static void tearDown() {
		System.setProperties(originalSystemProperties);
	}

	@Test
	public void testWithCosmosClient() {
		int n = 10;
		var assetFlux = Flux.range(1, n)
				.map(i ->
						Asset.builder()
								.id(String.valueOf(i))
								.assetId("a_" + i)
								.fileName((i % 2 == 0 ? "a_" : "b_") + i + ".png")
								.build());
		long t0 = System.currentTimeMillis();
		var count = reactiveAssetRepository.saveAll(assetFlux).count().block();
		long t1 = System.currentTimeMillis();

		log.info("saved {} assets in {}", count, (t1 - t0) / 1000.0);

		t0 = System.currentTimeMillis();
//        Flux<Asset> findByFileName = container.queryItems("SELECT * FROM c WHERE STARTSWITH(c.fileName, \"a\", false)", Asset.class);

		Flux<Asset> findByFileName = reactiveAssetRepository.findByFileNameStartingWith("a");

		findByFileName.as(StepVerifier::create)
				.thenConsumeWhile(v -> {
					assertThat(v.getFileName()).startsWith("a_");
					return true;
				})
				.verifyComplete();

		t1 = System.currentTimeMillis();
		Long tot = findByFileName.count().block();

		log.info("Loaded {} assets in {}", tot, (t1 - t0) / 1000.0);
		assertThat(tot).isEqualTo(n / 2);
	}
}
