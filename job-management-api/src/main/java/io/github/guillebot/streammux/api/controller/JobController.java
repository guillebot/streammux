package io.github.guillebot.streammux.api.controller;

import io.github.guillebot.streammux.api.service.JobService;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/jobs")
public class JobController {
    private final JobService jobService;

    public JobController(JobService jobService) { this.jobService = jobService; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) public JobDefinition create(@RequestBody JobDefinition definition) { return jobService.createJob(definition); }
    @GetMapping public Collection<JobDefinition> list() { return jobService.listJobs(); }
    @GetMapping("/{jobId}") public JobDefinition get(@PathVariable("jobId") String jobId) { return jobService.getJob(jobId); }
    @PutMapping("/{jobId}") public JobDefinition update(@PathVariable("jobId") String jobId, @RequestBody JobDefinition definition) { return jobService.updateJob(jobId, definition); }
    @PostMapping("/{jobId}/pause") @ResponseStatus(HttpStatus.ACCEPTED) public void pause(@PathVariable("jobId") String jobId) { jobService.issueCommand(jobId, CommandType.PAUSE); }
    @PostMapping("/{jobId}/resume") @ResponseStatus(HttpStatus.ACCEPTED) public void resume(@PathVariable("jobId") String jobId) { jobService.issueCommand(jobId, CommandType.RESUME); }
    @PostMapping("/{jobId}/restart") @ResponseStatus(HttpStatus.ACCEPTED) public void restart(@PathVariable("jobId") String jobId) { jobService.issueCommand(jobId, CommandType.RESTART); }
    @DeleteMapping("/{jobId}") @ResponseStatus(HttpStatus.ACCEPTED) public void delete(@PathVariable("jobId") String jobId) { jobService.deleteJob(jobId); }
    @GetMapping("/{jobId}/status") public Optional<JobRuntimeStatus> status(@PathVariable("jobId") String jobId) { return jobService.getStatus(jobId); }
    @GetMapping("/{jobId}/lease") public Optional<JobLease> lease(@PathVariable("jobId") String jobId) { return jobService.getLease(jobId); }
    @GetMapping("/{jobId}/events") public List<JobEvent> events(@PathVariable("jobId") String jobId) { return jobService.getEvents(jobId); }
}
