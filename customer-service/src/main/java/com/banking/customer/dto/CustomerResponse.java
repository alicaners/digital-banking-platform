package com.banking.customer.dto;

import java.time.LocalDateTime;

public class CustomerResponse {

    private Long id;
    private String firstName;
    private String lastName;
    private String identityNumber;
    private String email;
    private String phoneNumber;
    private String address;
    private LocalDateTime createdAt;

    public CustomerResponse(Long id, String firstName, String lastName, String identityNumber,
                            String email, String phoneNumber, String address, LocalDateTime createdAt) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.identityNumber = identityNumber;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.address = address;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getIdentityNumber() { return identityNumber; }
    public String getEmail() { return email; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getAddress() { return address; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}