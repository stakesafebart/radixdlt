package com.radixdlt.client.application.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.radixdlt.client.application.actions.DataStore;
import com.radixdlt.client.application.objects.Data;
import com.radixdlt.client.core.atoms.Atom;
import com.radixdlt.client.core.atoms.AtomBuilder;
import com.radixdlt.client.core.atoms.DataParticle;
import com.radixdlt.client.core.atoms.DataParticle.DataParticleBuilder;
import com.radixdlt.client.core.atoms.Payload;
import com.radixdlt.client.core.crypto.EncryptedPrivateKey;
import com.radixdlt.client.core.crypto.Encryptor;
import io.reactivex.Completable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DataStoreTranslator {
	private static final DataStoreTranslator INSTANCE = new DataStoreTranslator();
	private static final JsonParser JSON_PARSER = new JsonParser();

	public static DataStoreTranslator getInstance() {
		return INSTANCE;
	}

	private DataStoreTranslator() {
	}

	// TODO: figure out correct method signature here (return Single<AtomBuilder> instead?)
	public Completable translate(DataStore dataStore, AtomBuilder atomBuilder) {
		Payload payload = new Payload(dataStore.getData().getBytes());
		String application = (String) dataStore.getData().getMetaData().get("application");

		DataParticle dataParticle = new DataParticleBuilder()
			.payload(payload)
			.setMetaData("application", application)
			.build();
		atomBuilder.addDataParticle(dataParticle);
		Encryptor encryptor = dataStore.getData().getEncryptor();
		if (encryptor != null) {
			JsonArray protectorsJson = new JsonArray();
			encryptor.getProtectors().stream().map(EncryptedPrivateKey::base64).forEach(protectorsJson::add);

			Payload encryptorPayload = new Payload(protectorsJson.toString().getBytes(StandardCharsets.UTF_8));
			DataParticle encryptorParticle = new DataParticleBuilder()
				.payload(encryptorPayload)
				.setMetaData("application", "encryptor")
				.setMetaData("contentType", "application/json")
				.build();
			atomBuilder.addDataParticle(encryptorParticle);
		}
		//TODO: add this into data particle
		//dataStore.getAddresses().forEach(atomBuilder::addDestination);

		return Completable.complete();
	}

	public Optional<Data> fromAtom(Atom atom) {
		final Optional<DataParticle> bytesParticle = atom.getDataParticles().stream()
			.filter(p -> !"encryptor".equals(p.getMetaData("application")))
			.findFirst();

		if (!bytesParticle.isPresent()) {
			return Optional.empty();
		}

		// TODO: don't pass in maps, utilize a metadata builder?
		Map<String, Object> metaData = new HashMap<>();
		metaData.put("timestamp", atom.getTimestamp());
		metaData.put("signatures", atom.getSignatures());

		bytesParticle.ifPresent(p -> metaData.compute("application", (k, v) -> p.getMetaData("application")));

		final Optional<DataParticle> encryptorParticle = atom.getDataParticles().stream()
			.filter(p -> "encryptor".equals(p.getMetaData("application")))
			.findAny();
		metaData.put("encrypted", encryptorParticle.isPresent());

		final Encryptor encryptor;
		if (encryptorParticle.isPresent()) {
			JsonArray protectorsJson = JSON_PARSER.parse(encryptorParticle.get().getBytes().toUtf8String()).getAsJsonArray();
			List<EncryptedPrivateKey> protectors = new ArrayList<>();
			protectorsJson.forEach(protectorJson -> protectors.add(EncryptedPrivateKey.fromBase64(protectorJson.getAsString())));
			encryptor = new Encryptor(protectors);
		} else {
			encryptor = null;
		}

		return Optional.of(Data.raw(bytesParticle.get().getBytes().getBytes(), metaData, encryptor));
	}
}
