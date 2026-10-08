package com.binitech.elosys.application.ports.outbound;

import java.nio.file.Path;
import java.util.Map;

public interface LegacyImportPort {
  Map<String, Object> importSqlite(Path sqliteFile, boolean truncateFirst);
}
