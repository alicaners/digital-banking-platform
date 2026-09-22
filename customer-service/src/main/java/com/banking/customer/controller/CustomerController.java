package com.banking.customer.controller;

import com.banking.customer.dto.CustomerRequest;
import com.banking.customer.dto.CustomerResponse;
import com.banking.customer.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    @Autowired
    private CustomerService customerService;

    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(
            @Valid @RequestBody CustomerRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(customerService.createCustomer(request, userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> getCustomerById(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(customerService.getCustomerById(id, userId, role));
    }

    @GetMapping
    public ResponseEntity<List<CustomerResponse>> getAllCustomers(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(customerService.getAllCustomers(userId, role));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> updateCustomer(
            @PathVariable Long id,
            @Valid @RequestBody CustomerRequest request,
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(customerService.updateCustomer(id, request, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCustomer(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long userId) {
        customerService.deleteCustomer(id, userId);
        return ResponseEntity.noContent().build();
    }
}