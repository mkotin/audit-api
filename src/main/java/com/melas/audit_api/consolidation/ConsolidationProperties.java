package com.melas.audit_api.consolidation;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Consolidation automatique des dossiers.
 *
 * @param automatique poller actif : les dossiers dont une demande est en attente sont consolidés sans intervention ;
 *        désactivé, les demandes s'accumulent (compteur) et seul l'endpoint d'administration consolide
 * @param intervalle délai entre la fin d'un passage du poller et le début du suivant
 * @param parallelisme dossiers consolidés simultanément (exécuteur dédié, distinct de celui du traitement)
 * @param tailleFile dossiers en attente dans l'exécuteur ; au-delà, ils sont repris au passage suivant
 * @param delaisTentatives attente avant chaque nouvelle tentative après un échec technique : la n-ième tentative
 *        attend le n-ième délai, puis le dernier indéfiniment (pas de limite au nombre de tentatives)
 */
@Validated
@ConfigurationProperties("app.consolidation")
public record ConsolidationProperties(
		@DefaultValue("true") boolean automatique,
		@NotNull @DefaultValue("2s") Duration intervalle,
		@Min(1) @DefaultValue("2") int parallelisme,
		@Min(0) @DefaultValue("50") int tailleFile,
		@NotEmpty @DefaultValue({ "30s", "2m", "10m", "30m" }) List<Duration> delaisTentatives) {

	/** Attente avant la tentative numéro {@code tentative} (1 pour la première nouvelle tentative). */
	public Duration attenteAvant(int tentative) {
		int rang = Math.min(Math.max(tentative, 1), delaisTentatives.size());
		return delaisTentatives.get(rang - 1);
	}

}
