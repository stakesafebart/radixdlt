package com.radixdlt.client.core.atoms;

import com.google.gson.annotations.SerializedName;
import com.radixdlt.client.core.address.EUID;
import com.radixdlt.client.core.crypto.ECKeyPair;
import java.util.Collections;
import java.util.Set;

public class AssetParticle implements Particle {
	private final Set<ECKeyPair> owners;
	private final String type;
	@SerializedName("sub_units")
	private final long subUnits;
	@SerializedName("maximum_units")
	private final long maximumUnits;
	private final long settings;
	private final String iso;
	private final String label;
	private final String description;
	private final String classification;
	private final byte[] icon;
	private final EUID id;
	private final Spin spin;

	public AssetParticle(
		Set<ECKeyPair> owners,
		EUID id,
		String type,
		long subUnits,
		long maximumUnits,
		long settings,
		String iso,
		String label,
		String description,
		String classification,
		byte[] icon
	) {
		this.spin = Spin.UP;
		this.owners = owners;
		this.id = id;
		this.type = type;
		this.subUnits = subUnits;
		this.maximumUnits = maximumUnits;
		this.settings = settings;
		this.iso = iso;
		this.label = label;
		this.description = description;
		this.classification = classification;
		this.icon = icon;
	}

	// TODO: fix this to be an account
	public Set<EUID> getDestinations() {
		return Collections.singleton(id);
	}

	public Spin getSpin() {
		return spin;
	}
}
