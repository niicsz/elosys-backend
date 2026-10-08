package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.provenance.CollectionRef;
import com.binitech.elosys.domain.provenance.FileDigest;
import com.binitech.elosys.domain.provenance.ParseRequest;
import com.binitech.elosys.domain.provenance.SourceDefinition;
import java.util.List;
import java.util.Map;

public interface ProvenanceRepositoryPort {
  long sourceId(SourceDefinition source);

  CollectionRef recordCollection(
      long sourceId,
      String url,
      FileDigest payload,
      Integer httpStatus,
      String contentType,
      String collectorCommit,
      String notes);

  long recordFile(long collectionId, String filename, FileDigest digest);

  long recordParse(ParseRequest request, String parserCommit);

  void updateParseRows(long parseId, long rowsExtracted, long rowsRejected);

  void resetSource(String sourceName, List<String> dataTables);

  List<Map<String, Object>> manifestSources();

  List<CollectionToVerify> collectionsToVerify();

  record CollectionToVerify(long id, String url, String payloadSha256) {}
}
