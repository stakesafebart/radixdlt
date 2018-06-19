package com.radixdlt.client.core.address;

public final class RadixUniverseConfigs {

	private RadixUniverseConfigs() {
	}

	private final static String testUniverseDson = "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";

	private final static String highgardenUniverseDson = "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";

	private final static String alphanetUniverseDson = "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";

	public static final RadixUniverseConfig getWinterfell() {
		return RadixUniverseConfig.fromDson(testUniverseDson);
	}

	public static final RadixUniverseConfig getSunstone() {
		return RadixUniverseConfig.fromDson(testUniverseDson);
	}

	public static final RadixUniverseConfig getHighgarden() {
		return RadixUniverseConfig.fromDson(highgardenUniverseDson);
	}

	public static final RadixUniverseConfig getAlphanet() {
		return RadixUniverseConfig.fromDson(alphanetUniverseDson);
	}

}
