package com.radixdlt.atomos.test;

import com.radixdlt.atomos.AtomOS;
import com.radixdlt.atomos.FungibleDefinition;
import com.radixdlt.atomos.Result;
import com.radixdlt.atomos.mapper.ParticleToAmountMapper;
import com.radixdlt.atomos.mapper.ParticleToRRIMapper;
import com.radixdlt.atomos.mapper.ParticleToShardableMapper;
import com.radixdlt.atomos.mapper.ParticleToShardablesMapper;
import com.radixdlt.atoms.Particle;
import com.radixdlt.common.Pair;
import com.radixdlt.constraintmachine.AtomMetadata;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A stubbed AtomOS used for testing Atom Model Application Layer Code.
 * Note that this class is not thread-safe.
 */
public class TestAtomOS implements AtomOS {
	private final Map<Class<? extends Particle>, ParticleToRRIMapper<Particle>> resources = new HashMap<>();
	private final List<Pair<Class<? extends Particle>, BiFunction<Particle, AtomMetadata, Result>>> particleClassConstraints = new ArrayList<>();
	private final List<Pair<Pair<Class<? extends Particle>, Class<? extends Particle>>,
		ParticleClassWithDependenceConstraintCheck<? extends Particle, ?>>> particleClassWithDependencyConstraints = new ArrayList<>();
	private final List<FungibleDefinition<? extends Particle>> fungibleDefinitions = new ArrayList<>();
	private final List<Pair<Pair<Class<? extends Particle>, Class<? extends Particle>>,
		ParticleClassWithSideEffectConstraintCheck<? extends Particle, ?>>> particleClassWithSideEffectConstraints = new ArrayList<>();
	private FungibleDefinition.Builder<? extends Particle> pendingFungibleTransition = null;

	@Override
	public <T extends Particle> void registerParticle(Class<T> particleClass, ParticleToShardablesMapper<T> mapper) {
		// Not implemented for the test AtomOS for the time being as it is not used to test any functionality.
	}

	@Override
	public <T extends Particle> void registerParticle(Class<T> particleClass, ParticleToShardableMapper<T> mapper) {
		// Not implemented for the test AtomOS for the time being as it is not used to test any functionality.
	}

	@Override
	public <T extends Particle> void newResource(Class<T> particleClass, ParticleToRRIMapper<T> indexer) {
		resources.put(particleClass, p -> indexer.index((T) p));
	}

	@Override
	public <T extends Particle, U extends Particle> void newResource(
		Class<T> particleClass0,
		ParticleToRRIMapper<T> rriMapper0,
		Class<U> particleClass1,
		ParticleToRRIMapper<U> rriMapper1,
		BiPredicate<T, U> combinedResource
	) {
		resources.put(particleClass0, p -> rriMapper0.index((T) p));
	}

	@Override
	public <T extends Particle> TransitionlessParticleClassConstraint<T> onTransitionless(Class<T> particleClass) {
		return constraint -> particleClassConstraints.add(new Pair<>(particleClass, (p, m) -> constraint.apply((T) p, m)));
	}


	@Override
	public <T extends Particle> ParticleClassConstraint<T> on(Class<T> particleClass) {
		return constraint -> particleClassConstraints.add(new Pair<>(particleClass, (p, m) -> constraint.apply((T) p)));
	}

	@Override
	public <T extends Particle> FungibleTransitionConstraintStub<T> onFungible(
		Class<T> particleClass,
		ParticleToAmountMapper<T> particleToAmountMapper
	) {
		if (pendingFungibleTransition != null) {
			fungibleDefinitions.add(pendingFungibleTransition.build());
		}

		FungibleDefinition.Builder<T> transitionBuilder = new FungibleDefinition.Builder<T>()
			.of(particleClass, particleToAmountMapper);
		pendingFungibleTransition = transitionBuilder;

		return new FungibleTransitionConstraintStub<T>() {
			@Override
			public <U extends Particle> FungibleTransitionConstraint<T> requireInitialWith(
				Class<U> sideEffectClass,
				ParticleClassWithSideEffectConstraintCheck<T, U> constraint
			) {
				transitionBuilder.initialWith(sideEffectClass, constraint);
				return this::transitionTo;
			}

			@Override
			public <U extends Particle> FungibleTransitionConstraint<T> transitionTo(
				Class<U> particleClass,
				BiPredicate<T, U> transition,
				WitnessValidator<T> witnessValidator
			) {
				if (pendingFungibleTransition == null) {
					throw new IllegalStateException("Attempt to add formula to finished fungible transition to " + particleClass);
				}

				transitionBuilder.to(particleClass, witnessValidator, transition);
				return this::transitionTo;
			}
		};
	}

	/**
	 * Mimics a constraint check call to particle class checkers
	 *
	 * @param <T> class of particle
	 * @param t particle to be sent to the check call
	 * @param metadata
	 * @return list of results of each checker
	 */
	public <T extends Particle> TestResult testInitialParticle(T t, AtomMetadata metadata) {
		Stream<Result> resourceSigned =
			!resources.containsKey(t.getClass()) || metadata.isSignedBy(resources.get(t.getClass()).index(t).getAddress())
				? Stream.empty() : Stream.of(Result.error("Not signed"));

		Stream<Result> classConstraintResults = particleClassConstraints.stream()
			.filter(p -> p.getFirst().isAssignableFrom(t.getClass()))
			.map(Pair::getSecond)
			.map(constraint -> constraint.apply(t, metadata));

		final List<Result> results = Stream.concat(resourceSigned, classConstraintResults).collect(Collectors.toList());

		return new TestResult(results);
	}

	/**
	 * Mimics a constraint check call to a particle class with dependency constraint
	 *
	 * @param dependent The dependent
	 * @param dependency The dependency
	 * @param metadata The metadata
	 * @param <T> The dependent class
	 * @param <U> The dependency class
	 * @return list of results of each checker
	 */
	public <T extends Particle, U extends Particle> TestResult testParticleClassWithDependency(T dependent, U dependency, AtomMetadata metadata) {
		return testParticleClassWithDependency(Arrays.asList(dependent), dependency, metadata);
	}

	/**
	 * Mimics a constraint check call to a particle class with dependency constraint
	 *
	 * @param dependents The dependents
	 * @param dependency The dependency
	 * @param metadata The metadata
	 * @param <T> The dependent class
	 * @param <U> The dependency class
	 * @return list of results of each checker
	 */
	public <T extends Particle, U extends Particle> TestResult testParticleClassWithDependency(
		List<T> dependents,
		U dependency,
		AtomMetadata metadata
	) {
		if (dependents.isEmpty()) {
			throw new IllegalStateException("No dependents");
		}

		final List<Result> results = particleClassWithDependencyConstraints.stream()
			.filter(p -> p.getFirst().getFirst().isAssignableFrom(dependents.get(0).getClass()))
			.filter(p -> p.getFirst().getSecond().isAssignableFrom(dependency.getClass()))
			.map(Pair::getSecond)
			.map(constraint -> ((ParticleClassWithDependenceConstraintCheck<T, U>) constraint).check(dependents, dependency, metadata))
			.collect(Collectors.toList());

		return new TestResult(results);
	}

	/**
	 * Mimics a constraint check call to a particle class with side effect
	 *
	 * @param particle The particle
	 * @param sideEffect The side effect
	 * @param metadata The metadata
	 * @param <T> The particle type
	 * @param <U> The side effect type
	 * @return list of results of each checkers
	 */
	public <T extends Particle, U extends Particle> TestResult testParticleClassWithSideEffect(T particle, U sideEffect, AtomMetadata metadata) {
		final List<Result> results = particleClassWithSideEffectConstraints.stream()
			.filter(p -> p.getFirst().getFirst().isAssignableFrom(particle.getClass()))
			.filter(p -> p.getFirst().getSecond().isAssignableFrom(sideEffect.getClass()))
			.map(Pair::getSecond)
			.map(constraint -> ((ParticleClassWithSideEffectConstraintCheck<T, U>) constraint).check(particle, sideEffect, metadata))
			.collect(Collectors.toList());

		return new TestResult(results);
	}

	private void completePendingFungibleTransition() {
		if (pendingFungibleTransition != null) {
			fungibleDefinitions.add(pendingFungibleTransition.build());
			pendingFungibleTransition = null;
		}
	}
}
