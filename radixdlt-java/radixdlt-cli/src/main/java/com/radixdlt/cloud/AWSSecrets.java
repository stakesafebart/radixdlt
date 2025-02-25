package com.radixdlt.cloud;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.crypto.RadixKeyStore;
import com.radixdlt.identifiers.NodeAddressing;
import com.radixdlt.identifiers.ValidatorAddressing;
import com.radixdlt.networks.Network;
import com.radixdlt.utils.AWSSecretManager;
import com.radixdlt.utils.AWSSecretsOutputOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class AWSSecrets {
	private static final Boolean DEFAULT_ENABLE_AWS_SECRETS = false;
	private static final Boolean DEFAULT_RECREATE_AWS_SECRETS = false;
	private static final String DEFAULT_NETWORK_NAME = "testnet";
	private static final String FULLNODE_PREFIX = "fullnode";
	private static final String CORE_NODE_PREFIX = "node";
	public static final String KEYPAIR_NAME = "node";

	private AWSSecrets() {
	}

	public static void main(String[] args) {
		var options = new Options();
		options.addOption("h", "help", false, "Show usage information (this message)");
		options.addOption("n", "node-number", true, "Number of full nodes");
		options.addOption("p", "node-name-prefix", true, "Text prefix with which node name is numbered");
		options.addOption("s", "secret-password-key", true, "Password to encrypt key");
		options.addOption("as", "enable-aws-secrets", true, "Store as AWS Secrets(default: " + DEFAULT_ENABLE_AWS_SECRETS + ")");
		options.addOption("rs", "recreate-aws-secrets", true, "Recreate AWS Secrets(default: " + DEFAULT_RECREATE_AWS_SECRETS + ")");
		options.addOption("k", "network-name", true, "Network name(default: " + DEFAULT_NETWORK_NAME + ")");
		options.addOption("l", "node-names", true, "List of node names");

		var parser = new DefaultParser();
		try {
			var cmd = parser.parse(options, args);

			if (!cmd.getArgList().isEmpty()) {
				System.err.println("Extra arguments: " + cmd.getArgList().stream().collect(Collectors.joining(" ")));
				usage(options);
				return;
			}

			if (cmd.hasOption('h')) {
				usage(options);
				return;
			}
			final int nodeCount = getOption(cmd, 'n')
				.map(Integer::parseInt)
				.orElseThrow(() -> new IllegalArgumentException("Must specify number of nodes"));

			var namePrefix = getOption(cmd, 'p').orElse(FULLNODE_PREFIX);


			final var nodeNames = getOption(cmd, 'l')
				.orElseThrow(() -> new IllegalArgumentException("Must specify the list of nodenames"));

			var listOfNodes = Optional.ofNullable(nodeNames)
				.map(value -> Stream.of(value.split(","))
					.map(entry -> entry.replaceAll("[^\\w-]", ""))
					.collect(Collectors.toList()))
				.orElse(List.of());

			if (nodeCount <= 0 && listOfNodes.size() <= 0) {
				throw new IllegalArgumentException("There must be at least one node");
			}

			var networkName = getOption(cmd, 'k').orElse(DEFAULT_NETWORK_NAME);
			var defaultKeyPassword = getOption(cmd, 's').orElse("");

			boolean enableAwsSecrets = Boolean.parseBoolean(cmd.getOptionValue("as"));
			boolean recreateAwsSecrets = Boolean.parseBoolean(cmd.getOptionValue("rs"));

			var awsSecretsOutputOptions = new AWSSecretsOutputOptions(enableAwsSecrets, recreateAwsSecrets, networkName);

			var nodes = nodeCount > 0
						? IntStream.range(0, nodeCount)
							.mapToObj(counter -> String.format("%s%s", namePrefix, counter))
							.collect(Collectors.toList())
						: listOfNodes;

			System.out.println("name prefix " + namePrefix);
			generateAndStoreKey(networkName, namePrefix, defaultKeyPassword, awsSecretsOutputOptions, nodes);
			if (namePrefix.equals(CORE_NODE_PREFIX)) {
				System.out.println("Core node. Generate staking keys");
				generateAndStoreStakingKey(networkName, defaultKeyPassword, awsSecretsOutputOptions, nodes);
			}
		} catch (
			ParseException e) {
			System.out.println(e);
		}
	}

	private static void generateAndStoreKey(
		String networkName,
		String namePrefix,
		String defaultKeyPassword,
		AWSSecretsOutputOptions awsSecretsOutputOptions,
		List<String> nodes
	) {
		generateAndStoreKey(networkName, namePrefix, defaultKeyPassword, awsSecretsOutputOptions, nodes, Boolean.FALSE);
	}

	private static void generateAndStoreStakingKey(
		String networkName,
		String defaultKeyPassword,
		AWSSecretsOutputOptions awsSecretsOutputOptions,
		List<String> nodes
	) {
		generateAndStoreKey(networkName, CORE_NODE_PREFIX, defaultKeyPassword, awsSecretsOutputOptions, nodes, Boolean.TRUE);
	}

	private static void generateAndStoreKey(
		String networkName,
		String namePrefix,
		String defaultKeyPassword,
		AWSSecretsOutputOptions awsSecretsOutputOptions,
		List<String> nodes,
		Boolean isStaker
	) {
		Security.insertProviderAt(new BouncyCastleProvider(), 1);

		for (var nodeName : nodes) {
			var keyStoreName = String.format("%s.ks", nodeName);
			var keyStoreSecretName = String.format("%s.ks", nodeName);
			var passwordName = "password";
			var network = findNetwork(networkName.toUpperCase());
			var publicKeyFileSecretName = String.format("%s/%s/public_key", networkName, nodeName);

			if (namePrefix.equals(CORE_NODE_PREFIX)) {
				if (isStaker) {
					keyStoreSecretName = "staker_key";
					passwordName = "staker_password";
					keyStoreName = String.format("%s_stake.ks", nodeName);
					publicKeyFileSecretName = String.format("%s/%s/staker_public_key", networkName, nodeName);
				} else {
					keyStoreSecretName = "validator_key";
					passwordName = "validator_password";
					publicKeyFileSecretName = String.format("%s/%s/validator_public_key", networkName, nodeName);
				}
			}

			final var keyFileSecretName = String.format("%s/%s/%s", networkName, nodeName, keyStoreSecretName);
			final var passwordSecretName = String.format("%s/%s/%s", networkName, nodeName, passwordName);
			final var password = generatePassword(defaultKeyPassword);

			try {
				var keyFilePath = Paths.get(keyStoreName);
				var keystoreFile = new File(keyFilePath.toString());
				var keyPair = ECKeyPair.generateNew();

				RadixKeyStore.fromFile(keystoreFile, password.toCharArray(), !keystoreFile.canWrite())
					.writeKeyPair(KEYPAIR_NAME, keyPair);

				var keyFileAwsSecret = new HashMap<String, Object>();
				var publicKeyFileAwsSecret = new HashMap<String, Object>();

				putKeyBytes(keyFilePath, keyFileAwsSecret);
				printAddresses(network, keyPair, publicKeyFileAwsSecret);

				var keyPasswordAwsSecret = new HashMap<String, Object>();
				keyPasswordAwsSecret.put("key", password);

				writeBinaryAWSSecret(keyFileAwsSecret, keyFileSecretName, awsSecretsOutputOptions, false, true);
				writeBinaryAWSSecret(keyPasswordAwsSecret, passwordSecretName, awsSecretsOutputOptions, false, false);
				writeBinaryAWSSecret(publicKeyFileAwsSecret, publicKeyFileSecretName, awsSecretsOutputOptions, false, false);
			} catch (Exception e) {
				System.out.println(e);
			}
		}
	}

	private static void printAddresses(Network network, ECKeyPair keyPair, HashMap<String, Object> publicKeyFileAwsSecret) {
		var pubKey = keyPair.getPublicKey();
		var nodeAddressing = NodeAddressing.bech32(network.getNodeHrp());
		var validatorAddressing = ValidatorAddressing.bech32(network.getValidatorHrp());

		publicKeyFileAwsSecret.put("bech32", nodeAddressing.of(pubKey));
		publicKeyFileAwsSecret.put("hex", pubKey.toHex());
		publicKeyFileAwsSecret.put("validator_address", validatorAddressing.of(pubKey));

		System.out.println(nodeAddressing.of(pubKey));
		System.out.println(pubKey.toHex());
	}

	private static void putKeyBytes(Path keyFilePath, HashMap<String, Object> keyFileAwsSecret) {
		try {
			var data = Files.readAllBytes(keyFilePath);
			keyFileAwsSecret.put("key", data);
		} catch (IOException e) {
			throw new IllegalStateException("While reading validator keys", e);
		}
	}

	private static String generatePassword(String password) {
		if (password == null || password.isEmpty()) {
			//anphanumeric and special charactrers
			int asciiOrigin = 48;    //0
			int asciiBound = 122;    //z
			int passwordLength = 8;
			SecureRandom random = new SecureRandom();
			return random.ints(asciiOrigin, asciiBound + 1)
				.filter(i -> Character.isAlphabetic(i) || Character.isDigit(i))
				.limit(passwordLength)
				.collect(StringBuilder::new, StringBuilder::appendCodePoint,
						 StringBuilder::append
				)
				.toString();
		} else {
			return password;
		}
	}

	private static void usage(Options options) {
		new HelpFormatter().printHelp(AWSSecrets.class.getSimpleName(), options, true);
	}

	private static Optional<String> getOption(CommandLine cmd, char opt) {
		return Optional.ofNullable(cmd.getOptionValue(opt));
	}

	private static void writeBinaryAWSSecret(
		Map<String, Object> awsSecret, String secretName, AWSSecretsOutputOptions awsSecretsOutputOptions,
		boolean compress, boolean binarySecret
	) {
		if (!awsSecretsOutputOptions.getEnableAwsSecrets()) {
			System.out.println("Secret " + secretName + " not stored in AWS");
			return;
		}
		if (AWSSecretManager.awsSecretExists(secretName) && !canBeUpdated(awsSecretsOutputOptions)) {
			System.out.println("Secret " + secretName + " cannot be updated");
			return;
		}
		if (AWSSecretManager.awsSecretExists(secretName)) {
			AWSSecretManager.updateAWSSecret(awsSecret, secretName, awsSecretsOutputOptions, compress, binarySecret);
		} else {
			AWSSecretManager.createAWSSecret(awsSecret, secretName, awsSecretsOutputOptions, compress, binarySecret);
		}
	}

	private static boolean canBeUpdated(final AWSSecretsOutputOptions awsSecretsOutputOptions) {
		return awsSecretsOutputOptions.getRecreateAwsSecrets()
			&& (!awsSecretsOutputOptions.getNetworkName().equalsIgnoreCase("betanet")
					|| !awsSecretsOutputOptions.getNetworkName().equalsIgnoreCase("mainnet"));
	}

	private static Network findNetwork(String networkName) {
		var network = Network.ofName(networkName);

		if (network.isEmpty()) {
			System.out.println("Network " + networkName + " is not supported. Available networks: " + Arrays.toString(Network.values()));
		}

		return network.get();
	}
}
