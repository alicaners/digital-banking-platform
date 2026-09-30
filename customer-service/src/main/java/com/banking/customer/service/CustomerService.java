package com.banking.customer.service;

import com.banking.customer.dto.CustomerRequest;
import com.banking.customer.dto.CustomerResponse;
import com.banking.customer.entity.Customer;
import com.banking.customer.entity.Role;
import com.banking.customer.exception.AccessDeniedException;
import com.banking.customer.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public CustomerResponse createCustomer(CustomerRequest request, Long userId) {

        if (customerRepository.existsByIdentityNumber(request.getIdentityNumber())) {
            throw new IllegalArgumentException("Bu kimlik numarası zaten kayıtlı");
        }

        if (customerRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Bu email zaten kayıtlı");
        }

        Customer customer = new Customer();
        customer.setUserId(userId);
        customer.setFirstName(request.getFirstName());
        customer.setLastName(request.getLastName());
        customer.setIdentityNumber(request.getIdentityNumber());
        customer.setEmail(request.getEmail());
        customer.setPhoneNumber(request.getPhoneNumber());
        customer.setAddress(request.getAddress());

        customerRepository.save(customer);

        return toResponse(customer);
    }

    public CustomerResponse getCustomerById(Long id, Long userId, String role) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Müşteri bulunamadı"));

        checkReadAccess(customer, userId, role);

        return toResponse(customer);
    }

    public Page<CustomerResponse> getAllCustomers(Long userId, String role, Pageable pageable) {
        if (Role.valueOf(role) == Role.ADMIN) {
            return customerRepository.findAll(pageable)
                    .map(this::toResponse);
        }

        return customerRepository.findByUserId(userId, pageable)
                .map(this::toResponse);
    }

    public boolean existsById(Long id) {
        return customerRepository.existsById(id);
    }

    public CustomerResponse updateCustomer(Long id, CustomerRequest request, Long userId) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Müşteri bulunamadı"));

        checkWriteAccess(customer, userId);

        customer.setFirstName(request.getFirstName());
        customer.setLastName(request.getLastName());
        customer.setPhoneNumber(request.getPhoneNumber());
        customer.setAddress(request.getAddress());

        customerRepository.save(customer);

        return toResponse(customer);
    }

    public void deleteCustomer(Long id, Long userId) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Müşteri bulunamadı"));

        checkWriteAccess(customer, userId);

        customerRepository.deleteById(id);
    }

    private void checkReadAccess(Customer customer, Long userId, String role) {
        if (Role.valueOf(role) == Role.ADMIN) {
            return;
        }
        if (!customer.getUserId().equals(userId)) {
            throw new AccessDeniedException("Bu müşteri kaydına erişim yetkiniz yok");
        }
    }

    private void checkWriteAccess(Customer customer, Long userId) {
        if (!customer.getUserId().equals(userId)) {
            throw new AccessDeniedException("Bu müşteri kaydını değiştirme yetkiniz yok");
        }
    }

    private CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getIdentityNumber(),
                customer.getEmail(),
                customer.getPhoneNumber(),
                customer.getAddress(),
                customer.getCreatedAt()
        );
    }
}