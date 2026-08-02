package com.banking.customer.repository;

import com.banking.customer.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByIdentityNumber(String identityNumber);
    Optional<Customer> findByEmail(String email);
    boolean existsByIdentityNumber(String identityNumber);
    boolean existsByEmail(String email);
}