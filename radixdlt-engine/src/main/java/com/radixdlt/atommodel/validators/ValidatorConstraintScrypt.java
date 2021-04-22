/*
 * (C) Copyright 2020 Radix DLT Ltd
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

package com.radixdlt.atommodel.validators;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.TypeToken;
import com.radixdlt.atom.actions.Unknown;
import com.radixdlt.atomos.ConstraintScrypt;
import com.radixdlt.atomos.ParticleDefinition;
import com.radixdlt.atomos.Result;
import com.radixdlt.atomos.SysCalls;
import com.radixdlt.constraintmachine.Particle;
import com.radixdlt.constraintmachine.ReducerResult;
import com.radixdlt.constraintmachine.TransitionProcedure;
import com.radixdlt.constraintmachine.TransitionToken;
import com.radixdlt.constraintmachine.InputOutputReducer;
import com.radixdlt.constraintmachine.VoidReducerState;
import com.radixdlt.constraintmachine.SignatureValidator;
import com.radixdlt.crypto.ECPublicKey;
import com.radixdlt.identifiers.RadixAddress;
import com.radixdlt.store.ImmutableIndex;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Constraint Scrypt defining the Validator FSM, specifically the registration/unregistration flow.
 * <p>
 * The Validator FSM is implemented with two particles, UnregisteredValidatorParticle and RegisteredValidatorParticle,
 * both carrying the address of the validator in question and a nonce. The first unregistered Validator particle
 * for an address (with nonce 0) is virtualised as having an UP spin to initialise the FSM. Whenever a validator
 * (identified by their address) transitions between the two states, the nonce must increase (to ensure uniqueness).
 * The atom carrying a transition must be signed by the validator.
 */
public class ValidatorConstraintScrypt implements ConstraintScrypt {
	@Override
	public void main(SysCalls os) {
		os.registerParticle(ValidatorParticle.class, ParticleDefinition.<ValidatorParticle>builder()
			.staticValidation(checkAddressAndUrl(ValidatorParticle::getUrl))
			.virtualizeUp(p -> !p.isRegisteredForNextEpoch() && p.getUrl().isEmpty() && p.getName().isEmpty())
			.allowTransitionsFromOutsideScrypts() // to enable staking in TokensConstraintScrypt
			.build()
		);

		createTransition(os,
			ValidatorParticle.class,
			ValidatorParticle::getKey,
			ValidatorParticle.class,
			ValidatorParticle::getKey
		);
	}

	private <I extends Particle, O extends Particle> void createTransition(
		SysCalls os,
		Class<I> inputParticle,
		Function<I, ECPublicKey> inputAddressMapper,
		Class<O> outputParticle,
		Function<O, ECPublicKey> outputAddressMapper
	) {
		os.createTransition(
			new TransitionToken<>(inputParticle, outputParticle, TypeToken.of(VoidReducerState.class)),
			new ValidatorTransitionProcedure<>(inputAddressMapper, outputAddressMapper)
		);
	}

	// From the OWASP validation repository: https://www.owasp.org/index.php/OWASP_Validation_Regex_Repository
	private static final Pattern OWASP_URL_REGEX = Pattern.compile(
		"^((((https?|ftps?|gopher|telnet|nntp)://)|(mailto:|news:))"
		+ "(%[0-9A-Fa-f]{2}|[-()_.!~*';/?:@&=+$,A-Za-z0-9])+)([).!';/?:,][[:blank:]])?$"
	);

	private static <I> Function<I, Result> checkAddressAndUrl(Function<I, String> urlMapper) {
		return particle -> {
			String url = urlMapper.apply(particle);
			if (!url.isEmpty() && !OWASP_URL_REGEX.matcher(url).matches()) {
				return Result.error("url is not a valid URL: " + url);
			}

			return Result.success();
		};
	}

	// create a check verifying that the given address doesn't map to null
	private static <I> Function<I, Result> checkAddress(Function<I, RadixAddress> addressMapper) {
		return particle -> {
			if (addressMapper.apply(particle) == null) {
				return Result.error("address is null");
			}

			return Result.success();
		};
	}

	@VisibleForTesting
	static class ValidatorTransitionProcedure<I extends Particle, O extends Particle>
		implements TransitionProcedure<I, O, VoidReducerState> {
		private final Function<I, ECPublicKey> inputAddressMapper;
		private final Function<O, ECPublicKey> outputAddressMapper;

		ValidatorTransitionProcedure(
			Function<I, ECPublicKey> inputAddressMapper,
			Function<O, ECPublicKey> outputAddressMapper
		) {
			this.inputAddressMapper = inputAddressMapper;
			this.outputAddressMapper = outputAddressMapper;
		}

		@Override
		public Result precondition(I inputParticle, O outputParticle, VoidReducerState outputUsed, ImmutableIndex index) {
			var inputAddress = inputAddressMapper.apply(inputParticle);
			var outputAddress = outputAddressMapper.apply(outputParticle);
			// ensure transition is between validator particles concerning the same validator address
			if (!Objects.equals(inputAddress, outputAddress)) {
				return Result.error(String.format(
					"validator addresses do not match: %s != %s",
					inputAddress, outputAddress
				));
			}

			return Result.success();
		}

		@Override
		public InputOutputReducer<I, O, VoidReducerState> inputOutputReducer() {
			return (input, output, index, outputUsed) -> ReducerResult.complete(Unknown.create());
		}

		@Override
		public SignatureValidator<I, O> signatureValidator() {
			// verify that the transition was authenticated by the validator address in question
			return (i, o, index, pubKey) -> pubKey.map(inputAddressMapper.apply(i.getSubstate())::equals).orElse(false);
		}
	}
}
