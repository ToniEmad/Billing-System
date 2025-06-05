package com.telecom.dao;

import com.telecom.model.CustomerDetailsDTO;
import com.telecom.model.Invoice;
import com.telecom.model.Customer;
import com.telecom.model.InvoiceDetailsDTO;
import com.telecom.model.RatePlan;
import com.telecom.model.ServicePackage;
import com.telecom.util.DBConnection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

public class InvoiceDAO {

    private static final Logger LOGGER = Logger.getLogger(InvoiceDAO.class.getName());
    private final DBConnection dbConnection = new DBConnection();
    private final CustomerDAO customerDAO = new CustomerDAO();
    private final RatePlanDAO ratePlanDAO = new RatePlanDAO();
    private final ServicePackageDAO servicePackageDAO = new ServicePackageDAO();
    private final Random random = new Random();

    public void createInvoice(CustomerDetailsDTO customerDetails, String csvContent) throws SQLException {
        Customer customer = customerDetails.getCustomer();
        RatePlan ratePlan = customerDetails.getRatePlan();
        ServicePackage freeUnit = customerDetails.getFreeUnit();

        String invoiceId = "INV-" + System.currentTimeMillis();
        System.out.println("Generated Invoice ID: " + invoiceId);

        // Calculate rorUsage using random number
        BigDecimal rorUsage = calculateRorUsage(customer.getCreditLimit());
        System.out.println("Remaining (ROR) Usage for customer ID " + customer.getCustomerId() + ": " + rorUsage);

        BigDecimal monthlyFee = ratePlan.getMonthlyFee();
        BigDecimal freeUnitMonthlyFee = (freeUnit != null) ? freeUnit.getFreeUnitMonthlyFee() : BigDecimal.ZERO;
        BigDecimal occPrice = new BigDecimal(customer.getOccPrice());
        int monthsNumberInstallments = customer.getMonthsNumberInstallments();
        BigDecimal occMonthly = (monthsNumberInstallments > 0)
                ? occPrice.divide(new BigDecimal(monthsNumberInstallments), 2, BigDecimal.ROUND_HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal subtotal = monthlyFee.add(freeUnitMonthlyFee).add(occMonthly).add(rorUsage).setScale(2, BigDecimal.ROUND_HALF_UP);

        System.out.println("Monthly Fee: " + monthlyFee);
        System.out.println("Free Unit Monthly Fee: " + freeUnitMonthlyFee);
        System.out.println("OCC Price: " + occPrice);
        System.out.println("Installment Months: " + monthsNumberInstallments);
        System.out.println("OCC Monthly: " + occMonthly);
        System.out.println("Subtotal: " + subtotal);

        BigDecimal tax = new BigDecimal("10.00");
        BigDecimal taxMultiplier = BigDecimal.ONE.add(tax.divide(new BigDecimal("100.00"), 2, BigDecimal.ROUND_HALF_UP));
        BigDecimal totalBeforePromotion = subtotal.multiply(taxMultiplier).setScale(2, BigDecimal.ROUND_HALF_UP);
        System.out.println("Total before promotion (with 10% tax): " + totalBeforePromotion);

        BigDecimal promotionPackage = new BigDecimal(customer.getPromotionPackage());
        System.out.println("Promotion Package Value: " + promotionPackage);

        BigDecimal total = totalBeforePromotion.subtract(promotionPackage).max(BigDecimal.ZERO).setScale(2, BigDecimal.ROUND_HALF_UP);
        System.out.println("Final Total after Promotion: " + total);

        Invoice invoice = new Invoice();
        invoice.setInvoiceId(invoiceId);
        invoice.setCustomerId(customer.getCustomerId());
        invoice.setRorUsage(rorUsage);
        invoice.setSubtotal(subtotal);
        invoice.setTax(tax);
        invoice.setTotal(total);

        String sql = "INSERT INTO invoices (invoice_id, customer_id, ror_usage, subtotal, total) "
                + "VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = dbConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, invoice.getInvoiceId());
            stmt.setInt(2, invoice.getCustomerId());
            stmt.setBigDecimal(3, invoice.getRorUsage());
            stmt.setBigDecimal(4, invoice.getSubtotal());
            stmt.setBigDecimal(5, invoice.getTotal());

            int affectedRows = stmt.executeUpdate();
            if (affectedRows == 0) {
                throw new SQLException("Creating invoice failed, no rows affected.");
            }
            System.out.println("✅ Invoice inserted successfully for customer ID: " + customer.getCustomerId());
        } catch (SQLException e) {
            LOGGER.severe("❌ Error creating invoice for customer ID " + customer.getCustomerId() + ": " + e.getMessage());
            throw e;
        }
    }

    private BigDecimal calculateRorUsage(int creditLimit) {
        try {
            // Generate a random number less than creditLimit
            double randomNum = random.nextDouble() * creditLimit; // Random number between 0 and creditLimit
            BigDecimal randomBigDecimal = new BigDecimal(randomNum).setScale(2, BigDecimal.ROUND_HALF_UP);
            BigDecimal usage = new BigDecimal(creditLimit).subtract(randomBigDecimal).max(BigDecimal.ZERO);
            return usage.setScale(2, BigDecimal.ROUND_HALF_UP);
        } catch (Exception e) {
            LOGGER.severe("Error calculating ROR usage: " + e.getMessage());
            return BigDecimal.ZERO.setScale(2, BigDecimal.ROUND_HALF_UP);
        }
    }

    public void deleteAllInvoices() throws SQLException {
        String sql = "DELETE FROM invoices";

        try (Connection conn = dbConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            int affectedRows = stmt.executeUpdate();
            LOGGER.info("Deleted " + affectedRows + " invoices from the database.");
        } catch (SQLException e) {
            LOGGER.severe("Error deleting all invoices: " + e.getMessage());
            throw e;
        }
    }

    public List<Invoice> getAllInvoicesWithCustomerName() throws SQLException {
        List<Invoice> invoices = new ArrayList<>();
        String sql = "SELECT inv.*, cu.customer_id, cu.name FROM invoices inv "
                + "JOIN customers cu ON cu.customer_id = inv.customer_id";

        try (Connection conn = dbConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Invoice invoice = new Invoice();
                invoice.setInvoiceId(rs.getString("invoice_id"));
                invoice.setCustomerId(rs.getInt("customer_id"));
                invoice.setInvoiceDate(rs.getTimestamp("invoice_date"));
                invoice.setRorUsage(rs.getBigDecimal("ror_usage"));
                invoice.setSubtotal(rs.getBigDecimal("subtotal"));
                invoice.setTax(rs.getBigDecimal("tax"));
                invoice.setTotal(rs.getBigDecimal("total"));
                invoice.setCreatedAt(rs.getTimestamp("created_at"));

                Customer customer = new Customer();
                customer.setCustomerId(rs.getInt("customer_id"));
                customer.setName(rs.getString("name"));
                invoice.setCustomer(customer);

                invoices.add(invoice);
            }

            LOGGER.info("Retrieved " + invoices.size() + " invoices from the database.");
            return invoices;
        } catch (SQLException e) {
            LOGGER.severe("Error retrieving invoices: " + e.getMessage());
            throw e;
        }
    }

    public List<InvoiceDetailsDTO> getAllInvoicesWithDetails() throws SQLException {
        List<InvoiceDetailsDTO> invoiceDetailsList = new ArrayList<>();
        String sql = "SELECT invoice_id, customer_id, invoice_date, ror_usage, subtotal, tax, total, created_at FROM invoices";

        try (Connection conn = dbConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Invoice invoice = new Invoice();
                invoice.setInvoiceId(rs.getString("invoice_id"));
                invoice.setCustomerId(rs.getInt("customer_id"));
                invoice.setInvoiceDate(rs.getTimestamp("invoice_date"));
                invoice.setRorUsage(rs.getBigDecimal("ror_usage"));
                invoice.setSubtotal(rs.getBigDecimal("subtotal"));
                invoice.setTax(rs.getBigDecimal("tax"));
                invoice.setTotal(rs.getBigDecimal("total"));
                invoice.setCreatedAt(rs.getTimestamp("created_at"));

                Customer customer = customerDAO.getCustomerById(invoice.getCustomerId());
                if (customer == null) {
                    LOGGER.warning("Customer not found for ID: " + invoice.getCustomerId());
                    continue;
                }

                RatePlan ratePlan = ratePlanDAO.getRatePlanWithServices(customer.getPlanId());
                ServicePackage freeUnit = customerDAO.getFreeUnitDetails(customer.getFreeUnitId());

                InvoiceDetailsDTO dto = new InvoiceDetailsDTO(invoice, customer, ratePlan, freeUnit);
                invoiceDetailsList.add(dto);
            }

            LOGGER.info("Retrieved " + invoiceDetailsList.size() + " invoices with details from the database.");
            return invoiceDetailsList;
        } catch (SQLException e) {
            LOGGER.severe("Error retrieving invoices with details: " + e.getMessage());
            throw e;
        }
    }

    public Invoice getInvoiceById(String invoiceId) throws SQLException {
        String sql = "SELECT invoice_id, customer_id, invoice_date, ror_usage, subtotal, tax, total, created_at FROM invoices WHERE invoice_id = ?";
        try (Connection conn = dbConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, invoiceId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Invoice invoice = new Invoice();
                    invoice.setInvoiceId(rs.getString("invoice_id"));
                    invoice.setCustomerId(rs.getInt("customer_id"));
                    invoice.setInvoiceDate(rs.getTimestamp("invoice_date"));
                    invoice.setRorUsage(rs.getBigDecimal("ror_usage"));
                    invoice.setSubtotal(rs.getBigDecimal("subtotal"));
                    invoice.setTax(rs.getBigDecimal("tax"));
                    invoice.setTotal(rs.getBigDecimal("total"));
                    invoice.setCreatedAt(rs.getTimestamp("created_at"));
                    return invoice;
                }
                return null;
            }
        } catch (SQLException e) {
            LOGGER.severe("Error retrieving invoice with ID " + invoiceId + ": " + e.getMessage());
            throw e;
        }
    }

    public BigDecimal getTotalRevenue() throws SQLException {
        List<Invoice> invoices = getAllInvoicesWithCustomerName();
        return invoices.stream()
                .map(Invoice::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, BigDecimal.ROUND_HALF_UP);
    }
}