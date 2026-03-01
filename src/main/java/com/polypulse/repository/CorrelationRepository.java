package com.polypulse.repository;

import com.polypulse.model.Correlation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CorrelationRepository extends JpaRepository<Correlation, Long> {

    Page<Correlation> findByMarketIdOrderByDetectedAtDesc(Long marketId, Pageable pageable);

    Page<Correlation> findAllByOrderByDetectedAtDesc(Pageable pageable);

    boolean existsByMarketIdAndNewsEventId(Long marketId, Long newsEventId);

    long countByMarketId(Long marketId);
}
