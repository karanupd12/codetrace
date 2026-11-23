package dev.codetrace.impact.controller;

import dev.codetrace.common.web.Page;
import dev.codetrace.impact.controller.dto.ImpactReportRequest;
import dev.codetrace.impact.controller.dto.ImpactReportResponse;
import dev.codetrace.impact.controller.dto.ImpactRequestedResponse;
import dev.codetrace.impact.model.Finding;
import dev.codetrace.impact.model.ImpactReport;
import dev.codetrace.impact.service.ImpactReportService;
import dev.codetrace.jobs.controller.dto.JobResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Requesting and reading change impact reports. */
@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class ImpactReportController {
    private final ImpactReportService service;

    public ImpactReportController(ImpactReportService service) {
        this.service = service;
    }

    @PostMapping("/repositories/{id}/impact-reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImpactRequestedResponse request(@PathVariable UUID id, @Valid @RequestBody ImpactReportRequest req) {
        ImpactReportService.Requested r = service.request(id, req.baseCommit(), req.headCommit(), req.depth());
        return new ImpactRequestedResponse(response(r.report()), JobResponse.from(r.job()));
    }

    @GetMapping("/repositories/{id}/impact-reports")
    public List<ImpactReportResponse> list(@PathVariable UUID id) {
        return service.list(id).stream().map(this::response).toList();
    }

    @GetMapping("/impact-reports/{reportId}")
    public ImpactReportResponse get(@PathVariable UUID reportId) {
        return response(service.get(reportId));
    }

    @GetMapping("/impact-reports/{reportId}/findings")
    public Page<Finding> findings(@PathVariable UUID reportId, @RequestParam(required = false) String type,
                                  @RequestParam(required = false) String module,
                                  @RequestParam(required = false) Boolean test,
                                  @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return service.findings(reportId, type, module, test, limit, offset);
    }

    private ImpactReportResponse response(ImpactReport r) {
        return ImpactReportResponse.from(r, service.riskFactors(r), service.notes(r));
    }
}
