/*
 * (C) Copyright 2021 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.radixdlt.client.service;

import com.radixdlt.atom.actions.CreateMutableToken;
import com.radixdlt.atommodel.tokens.TokenDefinitionParticle;
import com.radixdlt.client.Rri;
import com.radixdlt.store.ImmutableIndex;
import com.radixdlt.utils.UInt256;
import com.radixdlt.utils.UInt384;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.radixdlt.client.api.TxHistoryEntry;
import com.radixdlt.client.store.ActionEntry;
import com.radixdlt.client.store.ClientApiStore;
import com.radixdlt.client.store.MessageEntry;
import com.radixdlt.client.store.TokenDefinitionRecord;
import com.radixdlt.identifiers.AID;
import com.radixdlt.identifiers.REAddr;
import com.radixdlt.identifiers.RadixAddress;
import com.radixdlt.universe.Universe;
import com.radixdlt.utils.functional.Result;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static com.radixdlt.client.store.berkeley.BalanceEntry.createBalance;

public class HighLevelApiServiceTest {
	private static final RadixAddress OWNER = RadixAddress.from("JEbhKQzBn4qJzWJFBbaPioA2GTeaQhuUjYWkanTE6N8VvvPpvM8");
	private static final RadixAddress TOKEN_ADDRESS = RadixAddress.from("23B6fH3FekJeP6e5guhZAk6n9z4fmTo5Tngo3a11Wg5R8gsWTV2x");
	private static final REAddr TOKEN = REAddr.ofHashedKey(TOKEN_ADDRESS.getPublicKey(), "xrd");

	private final Universe universe = mock(Universe.class);
	private final ClientApiStore clientApiStore = mock(ClientApiStore.class);

	private HighLevelApiService highLevelApiService;

	@Before
	public void setup() {
		var nativeTokenParticle = new TokenDefinitionParticle(
			TOKEN, "XRD", "XRD XRD", "", "", null, null
		);
		ImmutableIndex immutableIndex = (dbTxn, rriId) -> Optional.of(nativeTokenParticle);
		highLevelApiService = new HighLevelApiService(universe, clientApiStore, immutableIndex);
	}

	@Test
	public void testGetUniverseMagic() {
		when(universe.getMagic()).thenReturn(1234);

		assertEquals(1234, highLevelApiService.getUniverseMagic());
	}

	@Test
	public void testGetTokenBalancesForFunds() {
		var address = TOKEN_ADDRESS.getPublicKey();
		var balance1 = createBalance(OWNER.getPublicKey(), null, REAddr.ofHashedKey(address, "fff"), UInt384.FIVE);
		var balance2 = createBalance(OWNER.getPublicKey(), null, REAddr.ofHashedKey(address, "rar"), UInt384.NINE);
		var balances = Result.ok(List.of(balance1, balance2));

		when(clientApiStore.getTokenBalances(OWNER.getPublicKey(), false))
			.thenReturn(balances);

		highLevelApiService.getTokenBalances(OWNER.getPublicKey())
			.onSuccess(list -> {
				assertEquals(2, list.size());
				assertEquals(UInt384.FIVE, list.get(0).getAmount());
				assertEquals(UInt384.NINE, list.get(1).getAmount());
			})
			.onFailureDo(Assert::fail);
	}

	@Test
	@Ignore
	public void testGetTokenBalancesForStakes() {
		var address = TOKEN_ADDRESS.getPublicKey();
		var balance1 = createBalance(OWNER.getPublicKey(), null, REAddr.ofHashedKey(address, "fff"), UInt384.FIVE);
		var balance2 = createBalance(OWNER.getPublicKey(), null, REAddr.ofHashedKey(address, "rar"), UInt384.NINE);
		var balance3 = createBalance(OWNER.getPublicKey(), null, REAddr.ofNativeToken(), UInt384.TWO);
		var balances = Result.ok(List.of(balance1, balance2, balance3));

		when(clientApiStore.getTokenBalances(OWNER.getPublicKey(), true))
			.thenReturn(balances);

		highLevelApiService.getStakePositions(OWNER.getPublicKey())
			.onSuccess(list -> {
				assertEquals(3, list.size());
				assertEquals(UInt384.FIVE, list.get(0).getAmount());
				assertEquals(UInt384.NINE, list.get(1).getAmount());
				assertEquals(UInt384.TWO, list.get(2).getAmount());
			})
			.onFailureDo(Assert::fail);
	}

	@Test
	public void testGetTokenDescription() {
		var token = REAddr.ofHashedKey(TOKEN_ADDRESS.getPublicKey(), "fff");
		var definition = TokenDefinitionRecord.from(TOKEN_ADDRESS.getPublicKey(), mutableTokenDef("fff"));

		when(clientApiStore.parseRri(any()))
			.thenReturn(Result.ok(token));
		when(clientApiStore.getTokenDefinition(eq(token)))
			.thenReturn(Result.ok(definition));
		when(clientApiStore.getTokenSupply(token))
			.thenReturn(Result.ok(UInt384.NINE));

		var rri = Rri.of("fff", token);
		highLevelApiService.getTokenDescription(rri)
			.onSuccess(description -> assertEquals(token, description.rri()))
			.onSuccess(description -> assertEquals(UInt384.NINE, description.currentSupply()))
			.onFailureDo(Assert::fail);
	}

	@Test
	public void testGetTransactionHistory() {
		var entry = createTxHistoryEntry(AID.ZERO);

		when(clientApiStore.getTransactionHistory(eq(OWNER.getPublicKey()), eq(1), eq(Optional.empty())))
			.thenReturn(Result.ok(List.of(entry)));

		highLevelApiService.getTransactionHistory(OWNER.getPublicKey(), 1, Optional.empty())
			.onSuccess(tuple -> tuple.map((cursor, list) -> {
				assertTrue(cursor.isPresent());
				assertEquals(entry.timestamp(), cursor.get());

				assertEquals(1, list.size());
				assertEquals(entry, list.get(0));

				return null;
			}))
			.onFailureDo(Assert::fail);
	}

	@Test
	public void testGetTransaction() {
		var entry = createTxHistoryEntry(AID.ZERO);

		when(clientApiStore.getTransaction(AID.ZERO))
			.thenReturn(Result.ok(entry));

		highLevelApiService.getTransaction(entry.getTxId())
			.onSuccess(result -> assertEquals(entry, result))
			.onFailureDo(Assert::fail);
	}

	private TxHistoryEntry createTxHistoryEntry(AID txId) {
		var now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
		var action = ActionEntry.unknown();
		return TxHistoryEntry.create(
			txId, now, UInt256.ONE, MessageEntry.fromPlainString("text"), List.of(action)
		);
	}

	private CreateMutableToken mutableTokenDef(String symbol) {
		return new CreateMutableToken(
			symbol,
			symbol,
			description(symbol),
			iconUrl(symbol),
			homeUrl(symbol)
		);
	}

	private String description(String symbol) {
		return "Token with symbol " + symbol;
	}

	private String iconUrl(String symbol) {
		return "https://" + symbol.toLowerCase(Locale.US) + ".coin.com/icon";
	}

	private String homeUrl(String symbol) {
		return "https://" + symbol.toLowerCase(Locale.US) + ".coin.com/home";
	}
}