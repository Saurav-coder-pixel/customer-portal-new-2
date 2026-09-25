package com.cris.customerportal.service;

import com.cris.customerportal.audit.DatabaseOperationAudit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class DbaEmailService {

    private final JavaMailSender mailSender;
    private final String fromEmail;
    private final String dbaEmail;

    @Autowired
    public DbaEmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username:mondal.prasanta@cris.org.in}") String fromEmail,
            @Value("${app.dba-email:sura767848@gmail.com}") String dbaEmail) {
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
        this.dbaEmail = dbaEmail;
    }

    public void sendDbaAuditEmail(DatabaseOperationAudit audit) {
        if (audit == null) return;
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(dbaEmail);
            message.setSubject("Customer Registration Portal Database Query");

            StringBuilder sb = new StringBuilder();
            sb.append("Dear DBA Team,\n\n");
            sb.append("Please run the below script in rfrn3t and training\n\n");
            sb.append("QUERY:\n");
            sb.append(audit.getSqlStatement() != null ? audit.getSqlStatement() : "");
            sb.append("\n\nRegards,\nCustomer Registration Portal\nIT Team");

            message.setText(sb.toString());
            mailSender.send(message);
            System.out.println("[DBA EMAIL AUDIT] SMTP accepted notification to " + dbaEmail + " for " + audit.getOperationType() + " on " + audit.getTableName() + " (Code: " + audit.getCustomerCode() + ")");
        } catch (MailException e) {
            Throwable cause = e.getCause();
            if (cause != null && cause.getClass().getName().contains("SendFailedException")) {
                System.err.println("[DBA EMAIL AUDIT ERROR] SendFailedException: Failed to send DBA notification email: " + e.getMessage());
            } else if (cause != null && cause.getClass().getName().contains("MessagingException")) {
                System.err.println("[DBA EMAIL AUDIT ERROR] MessagingException: Failed to send DBA notification email: " + e.getMessage());
            } else {
                System.err.println("[DBA EMAIL AUDIT ERROR] MailException: Failed to send DBA notification email: " + e.getMessage());
            }
            // Log without stopping the calling process (core Oracle transaction will continue)
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[DBA EMAIL AUDIT ERROR] Unexpected error while sending DBA notification email: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Generic audit notification for document uploads (PAN / GSTIN).
     * Non-fatal: failures are logged but never propagated to the caller.
     */
    public void sendGenericAudit(String operation, String table, String code, String detail) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(dbaEmail);
            message.setSubject("Customer Portal Audit: " + operation);
            message.setText(
                "Operation : " + operation + "\n" +
                "Table     : " + table + "\n" +
                "Code      : " + code + "\n" +
                "Detail    : " + detail + "\n\n" +
                "Regards,\nCustomer Registration Portal"
            );
            mailSender.send(message);
        } catch (Exception e) {
            System.err.println("[AUDIT EMAIL] Failed to send generic audit for " + operation + ": " + e.getMessage());
        }
    }
}
