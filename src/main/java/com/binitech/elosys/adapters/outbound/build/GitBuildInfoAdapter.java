package com.binitech.elosys.adapters.outbound.build;

import com.binitech.elosys.application.ports.outbound.BuildInfoPort;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GitBuildInfoAdapter implements BuildInfoPort {
  private final String commit;

  public GitBuildInfoAdapter(@Value("${ELOSYS_COMMIT:}") String configured) {
    this.commit = configured == null || configured.isBlank() ? fromGit() : configured.strip();
  }

  @Override
  public String gitCommit() {
    return commit;
  }

  private static String fromGit() {
    try {
      Process p = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) return null;
      return out;
    } catch (Exception e) {
      return null;
    }
  }
}
