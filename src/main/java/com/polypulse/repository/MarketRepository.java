package com.polypulse.repository;

import com.polypulse.model.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketRepository extends JpaRepository<Market, Long> {

    List<Market> findByActiveTrue();

    Optional<Market> findByConditionId(String conditionId);

    long countByActiveTrue();
}
