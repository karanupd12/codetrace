package dev.codetrace.symbols.controller;

import dev.codetrace.common.web.Page;
import dev.codetrace.symbols.model.IndexedSymbol;
import dev.codetrace.symbols.model.ParseDiagnostic;
import dev.codetrace.symbols.service.SymbolService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read API over the symbols and diagnostics extracted by one index job. All results are bounded. */
@RestController
@Profile("api")
@RequestMapping("/api/v1/index-jobs/{jobId}")
public class SymbolController {
    private final SymbolService symbols;

    public SymbolController(SymbolService symbols) {
        this.symbols = symbols;
    }

    @GetMapping("/symbols")
    public Page<IndexedSymbol> symbols(@PathVariable UUID jobId, @RequestParam(required = false) String kind,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        return symbols.search(jobId, kind, q, limit, offset);
    }

    @GetMapping("/diagnostics")
    public Page<ParseDiagnostic> diagnostics(@PathVariable UUID jobId, @RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(defaultValue = "0") int offset) {
        return symbols.diagnostics(jobId, limit, offset);
    }
}
