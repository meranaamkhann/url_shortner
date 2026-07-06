package com.urlshortener.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "custom_domains")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(nullable = false, unique = true, length = 255)
    private String domain;

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Column(name = "verification_token", nullable = false, length = 255)
    private String verificationToken;
}
