package io.casehub.devtown.app.ledger;

import io.casehub.ledger.api.model.supplement.ComplianceSupplement;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_compliance_supplement")
public class DevtownComplianceSupplement extends ComplianceSupplement {
}
