package com.banking.customer.service;

import com.banking.customer.dto.CustomerRequest;
import com.banking.customer.dto.CustomerResponse;
import com.banking.customer.entity.Customer;
import com.banking.customer.exception.AccessDeniedException;
import com.banking.customer.repository.CustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

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

    public List<CustomerResponse> getAllCustomers(Long userId, String role) {
        if ("ADMIN".equals(role)) {
            return customerRepository.findAll()
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        return customerRepository.findAll()
                .stream()
                .filter(customer -> customer.getUserId().equals(userId))
                .map(this::toResponse)
                .toList();
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
        if ("ADMIN".equals(role)) {
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