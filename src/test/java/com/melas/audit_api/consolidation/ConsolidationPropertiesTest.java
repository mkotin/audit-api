package com.melas.audit_api.consolidation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Délai croissant avant chaque nouvelle tentative : le dernier se répète, sans limite. */
class ConsolidationPropertiesTest {

	private static final ConsolidationProperties PROPRIETES = new ConsolidationProperties(true, Duration.ofSeconds(2),
			2, 50, List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
					Duration.ofMinutes(30)));

	@ParameterizedTest
	@CsvSource({ "1, PT30S", "2, PT2M", "3, PT10M", "4, PT30M", "5, PT30M", "100, PT30M" })
	void attenteAvantLaTentative(int tentative, Duration attendue) {
		assertThat(PROPRIETES.attenteAvant(tentative)).isEqualTo(attendue);
	}

}
