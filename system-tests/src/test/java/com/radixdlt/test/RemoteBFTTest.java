package com.radixdlt.test;

import io.reactivex.Observable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class RemoteBFTTest {
	private final DockerBFTTestNetwork testNetwork;
	private final List<RemoteBFTCheck> checks = new ArrayList<>();

	public RemoteBFTTest(DockerBFTTestNetwork testNetwork) {
		this.testNetwork = Objects.requireNonNull(testNetwork);
	}

	public void waitUntilResponsive(long maxWaitTime, TimeUnit maxWaitTimeUnits) {
		Observable.interval(5, TimeUnit.SECONDS)
			.map(i -> testNetwork.checkResponsive(1, TimeUnit.SECONDS))
			.retry()
			.timeout(maxWaitTime, maxWaitTimeUnits)
			.take(1)
			.blockingSubscribe();
	}

	public void assertResponsiveness() {
		this.checks.add(new ResponsivenessCheck(1, TimeUnit.SECONDS, 1, TimeUnit.SECONDS));
	}

	public void run(long runtime, TimeUnit runtimeUnit) {
		List<Observable<Object>> assertions = checks.stream().map(check -> check.check(testNetwork)).collect(Collectors.toList());
		Observable.merge(assertions)
			.take(runtime, runtimeUnit)
			.blockingSubscribe();
	}

	public DockerBFTTestNetwork getTestNetwork() {
		return testNetwork;
	}
}
