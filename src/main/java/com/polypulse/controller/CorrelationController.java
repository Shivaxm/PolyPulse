package com.polypulse.controller;

import com.polypulse.dto.CorrelationDTO;
import com.polypulse.dto.CorrelationMapper;
import com.polypulse.repository.CorrelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/correlations")
@RequiredArgsConstructor
public class CorrelationController {

    private final CorrelationRepository correlationRepository;

    @GetMapping("/recent")
    public Map<String, Object> getRecent(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        List<Object[]> rows = correlationRepository.findRecentCorrelationsWithDetails(size, page * size);
        long total = correlationRepository.countAllCorrelations();

        List<CorrelationDTO> content = rows.stream().map(CorrelationMapper::fromRow).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", total);
        result.put("totalPages", (int) Math.ceil((double) total / size));
        result.put("number", page);
        result.put("size", size);
        result.put("last", (page + 1) * size >= total);
        return result;
    }
}
