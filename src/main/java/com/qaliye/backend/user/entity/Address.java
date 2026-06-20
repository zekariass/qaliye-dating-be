package com.qaliye.backend.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "country_code", nullable = false)
    private String countryCode;

    @Column(name = "country_name", nullable = false)
    private String countryName;

    @Column(nullable = false)
    private String city;

    private String region;

    // coords GEOGRAPHY(Point, 4326) — NOT MAPPED — use NamedParameterJdbcTemplate for spatial queries

    @Column(name = "formatted_address")
    private String formattedAddress;

    @Column(name = "location_source", nullable = false)
    private String locationSource;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
