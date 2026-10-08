package com.binitech.elosys.application.ports.outbound;

import java.nio.file.Path;

public interface JsonPort {
  Object readFile(Path path);

  Object parse(String json);

  String canonical(Object value);

  String write(Object value);

  String writePretty(Object value);
}
