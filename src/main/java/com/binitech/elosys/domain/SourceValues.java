package com.binitech.elosys.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SourceValues {
  private static final Set<String> TSE_NULLS =
      Set.of("", "#NULO#", "#NE#", "#NE", "#NULO", "N/A", "NULO");
  private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern WHITESPACE =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern DAY = Pattern.compile("3[01]|[12]\\d|0[1-9]|[1-9]");
  private static final Pattern MONTH = Pattern.compile("1[0-2]|0[1-9]|[1-9]");

  private SourceValues() {}

  public static String clean(String value) {
    if (value == null) return null;
    String v = value.strip();
    return isTseNull(v) ? null : v;
  }

  private static boolean isTseNull(String v) {
    if (TSE_NULLS.contains(v.toUpperCase(Locale.ROOT))) return true;
    return v.length() > 1 && v.charAt(0) == '-' && allAsciiDigits(v.substring(1));
  }

  public static String normalizeName(String name) {
    String cleaned = clean(name);
    if (cleaned == null || cleaned.isEmpty()) return null;
    String noAccents =
        COMBINING_MARKS.matcher(Normalizer.normalize(cleaned, Normalizer.Form.NFKD)).replaceAll("");
    String collapsed =
        WHITESPACE.matcher(noAccents.toUpperCase(Locale.ROOT).strip()).replaceAll(" ");
    return collapsed.isEmpty() ? null : collapsed;
  }

  public static String digitsOnly(String value) {
    String cleaned = clean(value);
    if (cleaned == null || cleaned.isEmpty()) return null;
    StringBuilder out = new StringBuilder(cleaned.length());
    for (int i = 0; i < cleaned.length(); i++) {
      char c = cleaned.charAt(i);
      if (c >= '0' && c <= '9') out.append(c);
    }
    return out.isEmpty() ? null : out.toString();
  }

  public static String exactDigits(String value, int length) {
    String digits = digitsOnly(value);
    return digits != null && digits.length() == length ? digits : null;
  }

  public static boolean cpfIsValid(String cpf) {
    if (cpf == null || cpf.length() != 11 || !allAsciiDigits(cpf)) return false;
    if (cpf.chars().distinct().count() == 1) return false;
    for (int cut : new int[] {9, 10}) {
      int total = 0;
      for (int i = 0; i < cut; i++) total += (cpf.charAt(i) - '0') * (cut + 1 - i);
      int check = (total * 10) % 11 % 10;
      if (check != cpf.charAt(cut) - '0') return false;
    }
    return true;
  }

  public static String isoDate(String ddmmyyyy) {
    String v = clean(ddmmyyyy);
    if (v == null || v.isEmpty()) return null;
    String[] parts = v.split("/", -1);
    if (parts.length != 3) return v;
    if (!DAY.matcher(parts[0]).matches() || !MONTH.matcher(parts[1]).matches()) return v;
    String y = parts[2];
    int year;
    if (y.length() == 4 && allAsciiDigits(y)) {
      year = Integer.parseInt(y);
    } else if (y.length() == 2 && allAsciiDigits(y)) {
      int yy = Integer.parseInt(y);
      year = yy < 69 ? 2000 + yy : 1900 + yy;
    } else {
      return v;
    }
    try {
      return LocalDate.of(year, Integer.parseInt(parts[1]), Integer.parseInt(parts[0])).toString();
    } catch (DateTimeException e) {
      return v;
    }
  }

  public static Long brlToCents(String value) {
    String v = clean(value);
    if (v == null || v.isEmpty()) return null;
    v = v.replace(" ", "");
    if (v.contains(",")) v = v.replace(".", "").replace(",", ".");
    try {
      return new BigDecimal(v)
          .movePointRight(2)
          .setScale(0, RoundingMode.HALF_EVEN)
          .longValueExact();
    } catch (NumberFormatException | ArithmeticException e) {
      return null;
    }
  }

  public static Integer parseIntOrNull(String value) {
    if (value == null || value.isEmpty()) return null;
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public static boolean allAsciiDigits(String s) {
    if (s == null || s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }
}
