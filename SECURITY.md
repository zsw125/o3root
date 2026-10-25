# Security policy

## Scope

This project **intentionally ships a working local privilege-escalation
exploit** for one firmware family, so "the exploit works" is expected and is
not a vulnerability by itself. What we do care about:

- persistence or changes that survive a reboot (by design a reboot removes
  root, KernelSU and the permissive state);
- data loss or corruption outside the documented kernel-memory writes;
- rootd exposure — the daemon must listen on the abstract socket `@o3p_rootd`
  and accept only `SO_PEERCRED` uid 0/2000;
- secrets or device identifiers leaking into the repository or into logs.

## Reporting

Please **do not** open a public issue for a security problem. Use GitHub's
private vulnerability reporting:

1. Open the **Security** tab of this repository.
2. Click **Report a vulnerability**.
3. Include the app version, device, firmware, kernel (`uname -r`) and a minimal
   reproduction. Redact serial numbers, SSIDs and credentials.

If private reporting is unavailable, open a *minimal* public issue asking for a
private channel — without technical details.

## Supported versions

Only the latest release is supported. See [`docs/SECURITY.md`](docs/SECURITY.md)
for risks, cleanup steps, and what the tool changes in kernel memory.
