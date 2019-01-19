package com.radix.acceptance.sending_a_data_transaction;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.radix.acceptance.SpecificProperties;
import com.radix.acceptance.particle_groups.ParticleGroups;
import com.radixdlt.client.application.RadixApplicationAPI;
import com.radixdlt.client.application.identity.RadixIdentities;
import com.radixdlt.client.application.identity.RadixIdentity;
import com.radixdlt.client.application.translate.Action;
import com.radixdlt.client.application.translate.atomic.AtomicAction;
import com.radixdlt.client.application.translate.data.DecryptedMessage;
import com.radixdlt.client.application.translate.data.SendMessageAction;
import com.radixdlt.client.application.translate.tokenclasses.CreateTokenAction.TokenSupplyType;
import com.radixdlt.client.application.translate.tokenclasses.MintTokensAction;
import com.radixdlt.client.application.translate.tokenclasses.TokenClassesState;
import com.radixdlt.client.application.translate.tokens.TokenClassReference;
import com.radixdlt.client.application.translate.tokens.TransferTokensToParticleGroupsMapper;
import com.radixdlt.client.application.translate.tokens.UnknownTokenException;
import com.radixdlt.client.atommodel.accounts.RadixAddress;
import com.radixdlt.client.core.Bootstrap;
import com.radixdlt.client.core.RadixUniverse;
import com.radixdlt.client.core.network.actions.SubmitAtomAction;
import com.radixdlt.client.core.network.actions.SubmitAtomReceivedAction;
import com.radixdlt.client.core.network.actions.SubmitAtomRequestAction;
import com.radixdlt.client.core.network.actions.SubmitAtomResultAction;
import com.radixdlt.client.core.network.actions.SubmitAtomResultAction.SubmitAtomResultActionType;
import com.radixdlt.client.core.network.actions.SubmitAtomSendAction;
import cucumber.api.PendingException;
import cucumber.api.java.After;
import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import io.reactivex.disposables.Disposable;
import io.reactivex.observers.BaseTestConsumer.TestWaitStrategy;
import io.reactivex.observers.TestObserver;
import org.radix.utils.UInt256;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static com.radixdlt.client.core.network.actions.SubmitAtomResultAction.SubmitAtomResultActionType.COLLISION;
import static com.radixdlt.client.core.network.actions.SubmitAtomResultAction.SubmitAtomResultActionType.STORED;
import static com.radixdlt.client.core.network.actions.SubmitAtomResultAction.SubmitAtomResultActionType.VALIDATION_ERROR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * See <a href="https://radixdlt.atlassian.net/browse/RLAU-94">RLAU-94</a>.
 */
public class SendingADataTransaction {
	static {
		if (!RadixUniverse.isInstantiated()) {
			RadixUniverse.bootstrap(Bootstrap.BETANET);
		}
	}

	private static final String ADDRESS = "address";
	private static final String NAME = "name";
	private static final String SYMBOL = "symbol";
	private static final String DESCRIPTION = "description";
	private static final String INITIAL_SUPPLY = "initialSupply";
	private static final String NEW_SUPPLY = "newSupply";
	private static final String GRANULARITY = "granularity";

	private static final long TIMEOUT_MS = 10_000L; // Timeout in milliseconds

	private RadixApplicationAPI api;
	private RadixIdentity identity;
	private RadixApplicationAPI otherApi;
	private RadixIdentity otherIdentity;

	private final List<TestObserver<SubmitAtomAction>> observers = Lists.newArrayList();
	private final List<Disposable> disposables = Lists.newArrayList();

	@After
	public void after() {
		this.disposables.forEach(Disposable::dispose);
		this.disposables.clear();
	}

	private void setupApi() {
		this.identity = RadixIdentities.createNew();
		this.api = RadixApplicationAPI.create(this.identity);
		this.disposables.add(this.api.pull());

		this.otherIdentity = RadixIdentities.createNew();
		this.otherApi = RadixApplicationAPI.create(this.otherIdentity);
		this.disposables.add(this.otherApi.pull());

		this.observers.clear();
	}

	@Given("^I have access to a suitable Radix network$")
	public void i_have_access_to_a_suitable_Radix_network() {
		this.identity = RadixIdentities.createNew();
		this.otherIdentity = RadixIdentities.createNew();
		this.api = RadixApplicationAPI.createDefaultBuilder()
			.identity(this.identity)
			.build();
		this.otherApi = RadixApplicationAPI.createDefaultBuilder()
			.identity(this.otherIdentity)
			.build();
		this.disposables.add(this.api.pull());

		this.observers.clear();
	}

	private void createAtomic(RadixApplicationAPI api, Action... actions) {
		TestObserver<SubmitAtomAction> observer = new TestObserver<>();

		api.execute(new AtomicAction(
			actions
		))
			.toObservable()
			.doOnNext(System.out::println)
			.subscribe(observer);

		this.observers.add(observer);
	}

	private void awaitAtomStatus(SubmitAtomResultActionType... finalStates) {
		awaitAtomStatus(this.observers.size(), finalStates);
	}

	private void awaitAtomStatus(int atomNumber, SubmitAtomResultActionType... finalStates) {
		ImmutableSet<SubmitAtomResultActionType> finalStatesSet = ImmutableSet.<SubmitAtomResultActionType>builder()
			.addAll(Arrays.asList(finalStates))
			.build();

		this.observers.get(atomNumber - 1)
			.awaitCount(4, TestWaitStrategy.SLEEP_100MS, TIMEOUT_MS)
			.assertSubscribed()
			.assertNoTimeout()
			.assertNoErrors()
			.assertValueAt(0, SubmitAtomRequestAction.class::isInstance)
			.assertValueAt(1, SubmitAtomSendAction.class::isInstance)
			.assertValueAt(2, SubmitAtomReceivedAction.class::isInstance)
			.assertValueAt(3, SubmitAtomResultAction.class::isInstance)
			.assertValueAt(3, i -> finalStatesSet.contains(SubmitAtomResultAction.class.cast(i).getType()));
	}

	private void awaitAtomValidationError(String partMessage) {
		awaitAtomValidationError(this.observers.size(), partMessage);
	}

	private void awaitAtomValidationError(int atomNumber, String partMessage) {
		this.observers.get(atomNumber - 1)
			.awaitCount(4, TestWaitStrategy.SLEEP_100MS, TIMEOUT_MS)
			.assertSubscribed()
			.assertNoTimeout()
			.assertNoErrors()
			.assertValueAt(0, SubmitAtomRequestAction.class::isInstance)
			.assertValueAt(1, SubmitAtomSendAction.class::isInstance)
			.assertValueAt(2, SubmitAtomReceivedAction.class::isInstance)
			.assertValueAt(3, SubmitAtomResultAction.class::isInstance)
			.assertValueAt(3, i -> SubmitAtomResultAction.class.cast(i).getType().equals(VALIDATION_ERROR))
			.assertValueAt(3, i -> SubmitAtomResultAction.class.cast(i).getData().getAsJsonObject().has("message"))
			.assertValueAt(3, i -> {
				String message = SubmitAtomResultAction.class.cast(i).getData().getAsJsonObject().get("message").getAsString();
				return message.contains(partMessage);
			});
	}

	private void awaitAtomException(Class<? extends Throwable> exceptionClass, String partialExceptionMessage) {
		awaitAtomException(this.observers.size(), exceptionClass, partialExceptionMessage);
	}

	private void awaitAtomException(int atomNumber, Class<? extends Throwable> exceptionClass, String partialExceptionMessage) {
		this.observers.get(atomNumber - 1)
			.awaitCount(3, TestWaitStrategy.SLEEP_100MS, TIMEOUT_MS)
			.assertError(exceptionClass)
			.assertError(t -> t.getMessage().contains(partialExceptionMessage));
	}

	@Then("^I can observe the atom being accepted$")
	public void i_can_observe_the_atom_being_accepted() {
		// "the atom" = most recent atom
		i_can_observe_atom_being_accepted(observers.size());
	}

	@Then("^I can observe atom (\\d+) being accepted$")
	public void i_can_observe_atom_being_accepted(int atomNumber) {
		awaitAtomStatus(atomNumber, STORED);
	}

	@Then("^I can observe the atom being rejected with a validation error$")
	public void i_can_observe_the_atom_being_rejected_as_a_validation_error() {
		// "the atom" = most recent atom
		i_can_observe_atom_being_rejected_as_a_validation_error(observers.size());
	}

	@Then("^I can observe atom (\\d+) being rejected with a validation error$")
	public void i_can_observe_atom_being_rejected_as_a_validation_error(int atomNumber) {
		awaitAtomStatus(atomNumber, VALIDATION_ERROR);
	}

	@Then("^I can observe the atom being rejected with an error$")
	public void i_can_observe_atom_being_rejected_with_an_error() {
		// "the atom" = most recent atom
		i_can_observe_atom_being_rejected_with_an_error(observers.size());
	}

	@Then("^I can observe atom (\\d+) being rejected with an error$")
	public void i_can_observe_atom_being_rejected_with_an_error(int atomNumber) {
		awaitAtomStatus(atomNumber, COLLISION, VALIDATION_ERROR);
	}

	@When("^I submit a message with \"([^\"]*)\" to another client claiming to be another client$")
	public void i_submit_a_message_with_to_another_client_claiming_to_be_another_client(String message) {
		createAtomic(this.api, new SendMessageAction(message.getBytes(), this.otherApi.getMyAddress(), this.otherApi.getMyAddress(), false));
	}

	@When("^I submit a message with \"([^\"]*)\" to another client$")
	public void i_submit_a_message_with_to_another_client(String message) {
		createAtomic(this.api, new SendMessageAction(message.getBytes(), this.api.getMyAddress(), this.otherApi.getMyAddress(), false));
	}

	@When("^I submit a message with \"([^\"]*)\" to myself$")
	public void i_submit_a_message_with_to_myself(String message) {
		createAtomic(this.api, new SendMessageAction(message.getBytes(), this.api.getMyAddress(), this.api.getMyAddress(), false));
	}

	@When("^I can observe a message with \"([^\"]*)\"$")
	public void i_can_observe_a_message_with(String message) {
		TestObserver<DecryptedMessage> messageTestObserver = new TestObserver<>();
		this.api.getMessages().subscribe(messageTestObserver);
		messageTestObserver.awaitCount(1);
		messageTestObserver.assertSubscribed();
		messageTestObserver.assertNoErrors();
		messageTestObserver.assertValue(m -> new String(m.getData()).equals(message));
		messageTestObserver.dispose();
	}

	@When("^I can observe a message with \"([^\"]*)\" from myself$")
	public void i_can_observe_a_message_with_from_myself(String message) {
		TestObserver<DecryptedMessage> messageTestObserver = new TestObserver<>();
		this.api.getMessages().subscribe(messageTestObserver);
		messageTestObserver.awaitCount(1);
		messageTestObserver.assertSubscribed();
		messageTestObserver.assertNoErrors();
		messageTestObserver.assertValue(m -> new String(m.getData()).equals(message) && m.getFrom().equals(this.api.getMyAddress()));
		messageTestObserver.dispose();
	}

	@When("^another client can observe a message with \"([^\"]*)\"$")
	public void another_client_can_observe_a_message_with(String message) {
		TestObserver<DecryptedMessage> messageTestObserver = new TestObserver<>();
		this.otherApi.getMessages().subscribe(messageTestObserver);
		messageTestObserver.awaitCount(1);
		messageTestObserver.assertSubscribed();
		messageTestObserver.assertNoErrors();
		messageTestObserver.assertValue(m -> new String(m.getData()).equals(message));
		messageTestObserver.dispose();
	}
}
