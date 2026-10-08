package com.binitech.elosys.adapters.inbound.web;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.inbound.JobRunView;
import com.binitech.elosys.application.ports.inbound.JobUseCasePort;
import com.binitech.elosys.domain.exception.NotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/jobs")
public class AdminJobController {
  private final JobUseCasePort jobs;

  public AdminJobController(JobUseCasePort jobs) {
    this.jobs = jobs;
  }

  @GetMapping
  public Map<String, Object> list() {
    return Map.of("available", jobs.available(), "recent", jobs.recent(20));
  }

  @PostMapping("/{name}")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public JobRunView start(
      @PathVariable String name, @RequestBody(required = false) Map<String, String> params) {
    return jobs.start(name, JobParameters.of(params == null ? Map.of() : params));
  }

  @GetMapping("/runs/{id}")
  public JobRunView run(@PathVariable long id) {
    return jobs.find(id).orElseThrow(() -> new NotFoundException("execução " + id + " não existe"));
  }
}
