package com.melas.audit_api.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Confiances stockées en numeric(4,3) : arrondies à 3 décimales. */
final class Confiance {

	private Confiance() {
	}

	static BigDecimal arrondir(double confiance) {
		if (confiance < 0 || confiance > 1) {
			throw new IllegalArgumentException("La confiance doit être comprise entre 0 et 1 : " + confiance);
		}
		return BigDecimal.valueOf(confiance).setScale(3, RoundingMode.HALF_UP);
	}

}
