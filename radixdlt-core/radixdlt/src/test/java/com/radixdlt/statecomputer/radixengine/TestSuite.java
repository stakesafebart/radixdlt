/*
 * (C) Copyright 2021 Radix DLT Ltd
 *
 * Radix DLT Ltd licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 */

package com.radixdlt.statecomputer.radixengine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.io.Files;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.radixdlt.SingleNodeAndPeersDeterministicNetworkModule;
import com.radixdlt.api.chaos.mempoolfiller.MempoolFillerModule;
import com.radixdlt.application.system.FeeTable;
import com.radixdlt.application.tokens.Amount;
import com.radixdlt.application.tokens.state.PreparedStake;
import com.radixdlt.application.tokens.state.PreparedUnstakeOwnership;
import com.radixdlt.application.tokens.state.TokenResource;
import com.radixdlt.application.validators.state.AllowDelegationFlag;
import com.radixdlt.application.validators.state.ValidatorMetaData;
import com.radixdlt.application.validators.state.ValidatorOwnerCopy;
import com.radixdlt.application.validators.state.ValidatorRakeCopy;
import com.radixdlt.application.validators.state.ValidatorRegisteredCopy;
import com.radixdlt.atom.MutableTokenDefinition;
import com.radixdlt.atom.TxBuilderException;
import com.radixdlt.atom.TxnConstructionRequest;
import com.radixdlt.atom.actions.BurnToken;
import com.radixdlt.atom.actions.CreateMutableToken;
import com.radixdlt.atom.actions.FeeReserveComplete;
import com.radixdlt.atom.actions.FeeReservePut;
import com.radixdlt.atom.actions.MintToken;
import com.radixdlt.atom.actions.NextEpoch;
import com.radixdlt.atom.actions.NextRound;
import com.radixdlt.atom.actions.RegisterValidator;
import com.radixdlt.atom.actions.StakeTokens;
import com.radixdlt.atom.actions.TransferToken;
import com.radixdlt.atom.actions.UnregisterValidator;
import com.radixdlt.atom.actions.UnstakeTokens;
import com.radixdlt.atom.actions.UpdateAllowDelegationFlag;
import com.radixdlt.consensus.LedgerProof;
import com.radixdlt.consensus.bft.Self;
import com.radixdlt.constraintmachine.PermissionLevel;
import com.radixdlt.constraintmachine.exceptions.MissingProcedureException;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.crypto.ECPublicKey;
import com.radixdlt.engine.RadixEngine;
import com.radixdlt.engine.RadixEngineException;
import com.radixdlt.identifiers.REAddr;
import com.radixdlt.mempool.MempoolConfig;
import com.radixdlt.qualifier.NumPeers;
import com.radixdlt.statecomputer.LedgerAndBFTProof;
import com.radixdlt.statecomputer.checkpoint.MockedGenesisModule;
import com.radixdlt.statecomputer.forks.ForksModule;
import com.radixdlt.statecomputer.forks.MainnetForkConfigsModule;
import com.radixdlt.statecomputer.forks.RERulesConfig;
import com.radixdlt.statecomputer.forks.RERulesVersion;
import com.radixdlt.statecomputer.forks.RadixEngineForksLatestOnlyModule;
import com.radixdlt.store.DatabaseLocation;
import com.radixdlt.store.LastStoredProof;
import com.radixdlt.store.berkeley.BerkeleyLedgerEntryStore;
import com.radixdlt.utils.PrivateKeys;
import com.radixdlt.utils.UInt256;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.radixdlt.utils.Bytes.toHexString;


public class TestSuite {
    private static final ECKeyPair VALIDATOR_KEY = PrivateKeys.ofNumeric(1);

    private static final ECKeyPair TEST_ACCOUNT = PrivateKeys.ofNumeric(2);
    private static final ECKeyPair TEST_ACCOUNT_2 = PrivateKeys.ofNumeric(3);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Inject
    private RadixEngine<LedgerAndBFTProof> sut;

    @Inject
    private BerkeleyLedgerEntryStore ledgerEntryStore;

    @Inject
    @LastStoredProof
    private LedgerProof ledgerProof;

    private Injector createInjector() {
        return Guice.createInjector(
                new MainnetForkConfigsModule(),
                new RadixEngineForksLatestOnlyModule(RERulesConfig.testingDefault().overrideFeeTable(
                    FeeTable.create(
                        Amount.ofMicroTokens(200), // 0.0002XRD per byte fee
                        Map.of(
                            TokenResource.class, Amount.ofTokens(1000), // 1000XRD per resource
                            ValidatorRegisteredCopy.class, Amount.ofTokens(5), // 5XRD per validator update
                            ValidatorRakeCopy.class, Amount.ofTokens(5), // 5XRD per register update
                            ValidatorOwnerCopy.class, Amount.ofTokens(5), // 5XRD per register update
                            ValidatorMetaData.class, Amount.ofTokens(5), // 5XRD per register update
                            AllowDelegationFlag.class, Amount.ofTokens(5), // 5XRD per register update
                            PreparedStake.class, Amount.ofMilliTokens(500), // 0.5XRD per stake
                            PreparedUnstakeOwnership.class, Amount.ofMilliTokens(500) // 0.5XRD per unstake
                        )
                    )
                )),
                new ForksModule(),
                new SingleNodeAndPeersDeterministicNetworkModule(VALIDATOR_KEY),
                new MockedGenesisModule(
                    Set.of(VALIDATOR_KEY.getPublicKey()),
                    Amount.ofTokens(101),
                    Amount.ofTokens(100)
                ).setTestAccount(TEST_ACCOUNT.getPublicKey(), Amount.ofTokens(1_000_000)),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bindConstant().annotatedWith(NumPeers.class).to(0);
                        bindConstant().annotatedWith(DatabaseLocation.class).to(folder.getRoot().getAbsolutePath());

                        install(MempoolConfig.asModule(1000, 200));
                        install(new MempoolFillerModule());
                    }
                }
        );
    }

    @Test
    public void generate_test_suite() throws TxBuilderException, RadixEngineException, IOException {
        Injector injector = createInjector();
        injector.injectMembers(this);
        var validator = injector.getInstance(Key.get(ECPublicKey.class, Self.class));

        var accountAddr = REAddr.ofPubKeyAccount(TEST_ACCOUNT.getPublicKey());
        var tokenAddr = REAddr.ofHashedKey(TEST_ACCOUNT.getPublicKey(), "symbol");
        var otherAccountAddr = REAddr.ofPubKeyAccount(TEST_ACCOUNT_2.getPublicKey());
        var tokDef = new MutableTokenDefinition(
            TEST_ACCOUNT.getPublicKey(),
            "symbol",
            "name",
            "description",
            "https://example.com/",
            "https://example.com/icon.png"
        );

        System.out.println("[This account] Create token resource...");
        var tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new CreateMutableToken(tokDef)
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        var results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/token_create.txt"));

        System.out.println("[This account] Mint resource...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .actions(List.of(
                        new MintToken(tokenAddr, accountAddr, UInt256.NINE)
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/token_mint.txt"));


        System.out.println("[This account] Transfer tokens...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .disableResourceAllocAndDestroy().actions(List.of(
                        new TransferToken(tokenAddr, accountAddr, otherAccountAddr, UInt256.ONE)
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/token_transfer.txt"));


        System.out.println("[This account] burn tokens...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .actions(List.of(
                        new BurnToken(tokenAddr, accountAddr, UInt256.ONE)
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/token_burn.txt"));


        System.out.println("[This account] Transfer XRD...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(

                        new TransferToken(REAddr.ofNativeToken(), accountAddr, otherAccountAddr, Amount.ofTokens(1_000).toSubunits())
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/xrd_transfer.txt"));

        System.out.println("[This account] Transfer XRD with message ...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .disableResourceAllocAndDestroy()
                .msg("Hello".getBytes(StandardCharsets.UTF_8))
                .feePayer(accountAddr)
                .actions(List.of(
                        new TransferToken(REAddr.ofNativeToken(), accountAddr, otherAccountAddr, Amount.ofTokens(5).toSubunits())
                )))
                .signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/xrd_transfer_with_msg.txt"));

        System.out.println("[Other account] Register validator...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(otherAccountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new RegisterValidator(TEST_ACCOUNT_2.getPublicKey())
                ))).signAndBuild(TEST_ACCOUNT_2::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/validator_register.txt"));

        System.out.println("[Other account] Unregister validator...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(otherAccountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new UnregisterValidator(TEST_ACCOUNT_2.getPublicKey())
                ))).signAndBuild(TEST_ACCOUNT_2::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/validator_unregister.txt"));

        System.out.println("[Other account] Re-register validator...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(otherAccountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new RegisterValidator(TEST_ACCOUNT_2.getPublicKey())
                ))).signAndBuild(TEST_ACCOUNT_2::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/validator_re_register.txt"));

        System.out.println("[Other account] Stake 200 XRD...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(otherAccountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new StakeTokens(otherAccountAddr, TEST_ACCOUNT_2.getPublicKey(), Amount.ofTokens(200).toSubunits())
                ))).signAndBuild(TEST_ACCOUNT_2::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/other_stake_from_validator_1.txt"));

        System.out.println("[Other account] Allow 3rd-party delegation...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(otherAccountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new UpdateAllowDelegationFlag(TEST_ACCOUNT_2.getPublicKey(), true)
                ))).signAndBuild(TEST_ACCOUNT_2::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/validator_allow_delegation.txt"));

        System.out.println("[Other account] Stake 200 XRD...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(otherAccountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new StakeTokens(otherAccountAddr, TEST_ACCOUNT_2.getPublicKey(), Amount.ofTokens(200).toSubunits())
                ))).signAndBuild(TEST_ACCOUNT_2::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/other_stake_from_validator_2.txt"));

        System.out.println("[This account] Stake 200 XRD...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new StakeTokens(accountAddr, TEST_ACCOUNT_2.getPublicKey(), Amount.ofTokens(200).toSubunits())
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/xrd_stake.txt"));

        // move epoch
        for (int i = 1; i <= 10; i++) {
            var nextEpoch = sut.construct(new NextRound(i, false, i, (x) -> validator))
                    .buildWithoutSignature();
            this.sut.execute(List.of(nextEpoch), null, PermissionLevel.SYSTEM);
        }
        var nextEpoch = sut.construct(new NextEpoch(11))
                .buildWithoutSignature();
        this.sut.execute(List.of(nextEpoch), null, PermissionLevel.SYSTEM);

        System.out.println("[This account] Unstake 100 XRD...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new UnstakeTokens(accountAddr, TEST_ACCOUNT_2.getPublicKey(), Amount.ofTokens(100).toSubunits())
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/xrd_unstake1.txt"));

        System.out.println("[This account] Unstake 100 XRD...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new UnstakeTokens(accountAddr, TEST_ACCOUNT_2.getPublicKey(), Amount.ofTokens(100).toSubunits())
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/xrd_unstake2.txt"));


        System.out.println("[This account] Transfer to self ...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new TransferToken(REAddr.ofNativeToken(), accountAddr, accountAddr, Amount.ofTokens(5).toSubunits())
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/other_transfer_to_self.txt"));


        System.out.println("[This account] Transfer XRD and token ...");
        tx = sut.construct(TxnConstructionRequest.create()
                .feePayer(accountAddr)
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new TransferToken(REAddr.ofNativeToken(), accountAddr, otherAccountAddr, Amount.ofTokens(5).toSubunits()),
                        new TransferToken(tokenAddr, accountAddr, otherAccountAddr, UInt256.TWO)
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/other_transfer_mixed_tokens.txt"));

        System.out.println("[This account] Transfer XRD and token ...");
        tx = sut.construct(TxnConstructionRequest.create()
                .disableResourceAllocAndDestroy()
                .actions(List.of(
                        new FeeReservePut(accountAddr, Amount.ofTokens(5).toSubunits()),
                        new TransferToken(tokenAddr, accountAddr, otherAccountAddr, UInt256.TWO),
                        new FeeReserveComplete(accountAddr)
                ))).signAndBuild(TEST_ACCOUNT::sign);
        System.out.println(toHexString(tx.getPayload()));
        results = sut.execute(List.of(tx));
        Files.write(toHexString(tx.getPayload()).getBytes(), new File("/Users/yulong/Desktop/docs/transaction-specs/test/samples/other_complex_fee.txt"));

    }

    @Test
    public void dumpProcedures() throws MissingProcedureException {
        var m = new MainnetForkConfigsModule();
        var config = m.olympia();
        var rules = RERulesVersion.OLYMPIA_V1.create(config.getConfig());
        var procedures = rules.getConstraintMachineConfig().getProcedures();
        var keys = new ArrayList<>(procedures.procedures.keySet());
        keys.sort(Comparator.comparing(k -> k.currentState.getSimpleName()));
        for (var k : keys) {
            System.out.printf("| %s | `%s` | %s | `%s` |%n", k.currentState.getSimpleName(), k.opSignature.op, (
                            k.opSignature.type instanceof Class ? ((Class<?>) k.opSignature.type).getSimpleName()
                                    : (k.opSignature.type == null ? "" : k.opSignature.type))
                    , procedures.getProcedure(k));
        }
    }
}
