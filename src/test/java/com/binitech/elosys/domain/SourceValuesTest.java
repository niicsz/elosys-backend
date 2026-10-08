package com.binitech.elosys.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceValuesTest {

  @Test
  void tseNullSentinelsBecomeNull() {
    assertThat(SourceValues.clean("#NULO#")).isNull();
    assertThat(SourceValues.clean("  #ne ")).isNull();
    assertThat(SourceValues.clean("-1")).isNull();
    assertThat(SourceValues.clean("-4")).isNull();
    assertThat(SourceValues.clean("")).isNull();
    assertThat(SourceValues.clean("-")).isEqualTo("-");
    assertThat(SourceValues.clean(" SP ")).isEqualTo("SP");
  }

  @Test
  void normalizeNameStripsAccentsAndCollapsesSpaces() {
    assertThat(SourceValues.normalizeName("  José   da  Conceição "))
        .isEqualTo("JOSE DA CONCEICAO");
    assertThat(SourceValues.normalizeName("#NULO#")).isNull();
  }

  @Test
  void cpfValidation() {
    assertThat(SourceValues.cpfIsValid("52998224725")).isTrue();
    assertThat(SourceValues.cpfIsValid("52998224724")).isFalse();
    assertThat(SourceValues.cpfIsValid("11111111111")).isFalse();
    assertThat(SourceValues.cpfIsValid("123")).isFalse();
  }

  @Test
  void digits() {
    assertThat(SourceValues.digitsOnly("529.982.247-25")).isEqualTo("52998224725");
    assertThat(SourceValues.exactDigits("529.982.247-25", 11)).isEqualTo("52998224725");
    assertThat(SourceValues.exactDigits("12.345", 11)).isNull();
    assertThat(SourceValues.digitsOnly("abc")).isNull();
  }

  @Test
  void isoDateKeepsUnparseableValuesVerbatim() {
    assertThat(SourceValues.isoDate("03/09/2026")).isEqualTo("2026-09-03");
    assertThat(SourceValues.isoDate("3/9/2026")).isEqualTo("2026-09-03");
    assertThat(SourceValues.isoDate("03/09/26")).isEqualTo("2026-09-03");
    assertThat(SourceValues.isoDate("03/09/75")).isEqualTo("1975-09-03");
    assertThat(SourceValues.isoDate("31/02/2026")).isEqualTo("31/02/2026");
    assertThat(SourceValues.isoDate("2026-09-03")).isEqualTo("2026-09-03");
    assertThat(SourceValues.isoDate("#NULO#")).isNull();
  }

  @Test
  void brlToCents() {
    assertThat(SourceValues.brlToCents("1.234,56")).isEqualTo(123456L);
    assertThat(SourceValues.brlToCents("1234.56")).isEqualTo(123456L);
    assertThat(SourceValues.brlToCents("1000.0")).isEqualTo(100000L);
    assertThat(SourceValues.brlToCents("10")).isEqualTo(1000L);
    assertThat(SourceValues.brlToCents("abc")).isNull();
    assertThat(SourceValues.brlToCents("#NULO#")).isNull();
  }
}
