package com.cris.customerportal.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * JPA entity for the MEMUSERS table (defined in database/legacy-oracle/06_create_users_table.sql).
 * Passwords are stored as bcrypt hashes -- never plaintext.
 */
@Entity
@Table(name = "MEMUSERS")
public class MemUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "MAVUSERID")
    private Long userId;

    @Column(name = "MAVEMAIL", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "MAVUSERNAME", nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "MAVPASSWORDHASH", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "MACACTIVEFLAG", length = 1)
    private String activeFlag = "Y";

    @Column(name = "MADCREATEDDATE")
    private LocalDate createdDate;

    public MemUser() {}

    public MemUser(String email, String username, String passwordHash) {
        this.email = email;
        this.username = username;
        this.passwordHash = passwordHash;
        this.activeFlag = "Y";
        this.createdDate = LocalDate.now();
    }

    public Long getUserId()                          { return userId; }
    public void setUserId(Long userId)               { this.userId = userId; }
    public String getEmail()                         { return email; }
    public void setEmail(String email)               { this.email = email; }
    public String getUsername()                      { return username; }
    public void setUsername(String username)         { this.username = username; }
    public String getPasswordHash()                  { return passwordHash; }
    public void setPasswordHash(String h)            { this.passwordHash = h; }
    public String getActiveFlag()                    { return activeFlag; }
    public void setActiveFlag(String activeFlag)     { this.activeFlag = activeFlag; }
    public LocalDate getCreatedDate()                { return createdDate; }
    public void setCreatedDate(LocalDate d)          { this.createdDate = d; }
}
