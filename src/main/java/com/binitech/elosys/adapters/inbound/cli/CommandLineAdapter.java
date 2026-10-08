package com.binitech.elosys.adapters.inbound.cli;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.inbound.JobRunView;
import com.binitech.elosys.application.ports.inbound.JobUseCasePort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

@Component
public class CommandLineAdapter implements ApplicationRunner, ExitCodeGenerator {
  private final JobUseCasePort jobs;
  private int exitCode;

  public CommandLineAdapter(JobUseCasePort jobs) {
    this.jobs = jobs;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> positional = args.getNonOptionArgs();
    if (positional.isEmpty()) return;
    String command = positional.getFirst();
    if (command.equals("help")) {
      System.out.println("comandos disponíveis:");
      jobs.available()
          .forEach(
              j ->
                  System.out.printf(
                      "  %-32s %s%s%n",
                      j.name(), j.description(), j.writesData() ? "" : " (somente leitura)"));
      return;
    }
    Map<String, String> options = new LinkedHashMap<>();
    for (String name : args.getOptionNames()) {
      List<String> values = args.getOptionValues(name);
      options.put(name, values == null || values.isEmpty() ? "" : String.join(",", values));
    }
    JobRunView run = jobs.runNow(command, JobParameters.of(options));
    if ("succeeded".equals(run.status())) {
      System.out.println(run.reportJson());
    } else {
      System.err.println("falhou: " + run.error());
      exitCode = 1;
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
