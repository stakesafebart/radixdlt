package com.radix.regression.doublespend;

import com.radixdlt.client.application.translate.ShardedAppStateId;
import com.radixdlt.client.application.translate.tokens.CreateTokenAction;
import com.radixdlt.client.application.translate.tokens.CreateTokenAction.TokenSupplyType;
import com.radixdlt.client.application.translate.tokens.TokenBalanceState;
import com.radixdlt.client.application.translate.tokens.TransferTokensAction;
import com.radixdlt.client.atommodel.accounts.RadixAddress;
import com.radixdlt.client.core.atoms.particles.RRI;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Condition;
import org.radix.common.tuples.Pair;

public class DoubleSpendMultiConflictTestConditions implements DoubleSpendTestConditions {
	private final RadixAddress apiAddress;
	private final RadixAddress toAddress;
	private final RRI tokenRef1;
	private final RRI tokenRef2;
	private final RRI tokenRef3;

	public DoubleSpendMultiConflictTestConditions(RadixAddress apiAddress, RadixAddress toAddress) {
		this.apiAddress = apiAddress;
		this.toAddress = toAddress;
		this.tokenRef1 = RRI.of(apiAddress, "JOSH1");
		this.tokenRef2 = RRI.of(apiAddress, "JOSH2");
		this.tokenRef3 = RRI.of(apiAddress, "JOSH3");
	}

	@Override
	public List<BatchedActions> initialActions() {
		return Collections.singletonList(
			new BatchedActions(
				CreateTokenAction.create(
					this.tokenRef1,
					"Joshy Token",
					"Cool Token",
					BigDecimal.TEN,
					BigDecimal.ONE,
					TokenSupplyType.FIXED
				)
			)
		);
	}

	@Override
	public List<List<BatchedActions>> conflictingActions() {
		return Arrays.asList(
			Arrays.asList(
				new BatchedActions(
					CreateTokenAction.create(
						this.tokenRef3,
						"Joshy Token 3",
						"Cool Token",
						BigDecimal.ONE,
						BigDecimal.ONE,
						TokenSupplyType.FIXED
					)
				),
				new BatchedActions(
					TransferTokensAction.create(apiAddress, toAddress, BigDecimal.ONE, tokenRef1),
					CreateTokenAction.create(
						this.tokenRef2,
						"Joshy Token 2",
						"Cool Token",
						BigDecimal.TEN,
						BigDecimal.ONE,
						TokenSupplyType.FIXED
					)
				)
			),
			Arrays.asList(
				new BatchedActions(
					CreateTokenAction.create(
						this.tokenRef2,
						"Joshy Token 2",
						"Cool Token",
						BigDecimal.ONE,
						BigDecimal.ONE,
						TokenSupplyType.FIXED
					)
				),
				new BatchedActions(
					TransferTokensAction.create(apiAddress, toAddress, BigDecimal.ONE, tokenRef1),
					CreateTokenAction.create(
						this.tokenRef3,
						"Joshy Token 3",
						"Cool Token",
						BigDecimal.TEN,
						BigDecimal.ONE,
						TokenSupplyType.FIXED
					)
				)
			)
		);
	}

	@Override
	public PostConsensusCondition postConsensusCondition() {
		Set<Pair<String, ShardedAppStateId>> stateRequired = new HashSet<>();
		stateRequired.add(Pair.of("Balance", ShardedAppStateId.of(TokenBalanceState.class, apiAddress)));

		return new PostConsensusCondition(
			stateRequired,
			new Condition<>(map -> {
				TokenBalanceState balanceState = (TokenBalanceState) map.get(ShardedAppStateId.of(TokenBalanceState.class, apiAddress));

				Map<RRI, BigDecimal> balances = balanceState.getBalance();

				if (balances.containsKey(tokenRef1) && balances.containsKey(tokenRef2) && balances.containsKey(tokenRef3)) {
					return (balances.get(tokenRef1).compareTo(BigDecimal.TEN) == 0
						&& balances.get(tokenRef2).compareTo(BigDecimal.ONE) == 0
						&& balances.get(tokenRef3).compareTo(BigDecimal.ONE) == 0)
					||	(balances.get(tokenRef1).compareTo(BigDecimal.valueOf(9)) == 0
						&& balances.get(tokenRef2).compareTo(BigDecimal.TEN) == 0
						&& balances.get(tokenRef3).compareTo(BigDecimal.ONE) == 0)
					||	(balances.get(tokenRef1).compareTo(BigDecimal.valueOf(9)) == 0
						&& balances.get(tokenRef2).compareTo(BigDecimal.ONE) == 0
						&& balances.get(tokenRef3).compareTo(BigDecimal.TEN) == 0);
				}

				return false;

			}, "10/1/1 or 9/10/0 or 9/0/10 in account")
		);
	}
}
