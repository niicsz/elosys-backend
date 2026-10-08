package com.binitech.elosys.application.usecases;

import com.binitech.elosys.application.ports.outbound.FileStoragePort.CsvRecord;
import com.binitech.elosys.domain.SourceValues;
import java.util.List;
import java.util.Locale;

public final class Csv {
  private Csv() {}

  public static String first(CsvRecord row, String... names) {
    for (String n : names) {
      if (row.has(n)) return SourceValues.clean(row.get(n));
    }
    return null;
  }

  public static String firstPresent(CsvRecord row, String... names) {
    for (String n : names) {
      if (row.has(n) && row.get(n) != null) return SourceValues.clean(row.get(n));
    }
    return null;
  }

  public static String raw(CsvRecord row, String name) {
    return row.get(name);
  }

  public static int yearOr(String value, int fallback) {
    if (value == null || value.isEmpty()) return fallback;
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public static List<String> preferBrasil(List<String> members) {
    List<String> brasil =
        members.stream().filter(n -> n.toLowerCase(Locale.ROOT).contains("_brasil")).toList();
    return (brasil.isEmpty() ? members : brasil).stream().sorted().toList();
  }
}
