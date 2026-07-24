-- Seed default roles used by Role-Based Access Control (RBAC).
INSERT INTO roles (name, description) VALUES
    ('ROLE_USER', 'Standard authenticated user: can create/manage own URLs'),
    ('ROLE_ADMIN', 'Administrator: can manage all users and URLs, view system-wide analytics');
