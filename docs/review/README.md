# Final review of 5cae5386 (14 Nov 2026, incomplete)

The review was stopped early because of usage limits. Finished reports: BLUE (vault, credentials, leaks), RED-ORDO (AI lock bypass, attack surface), REVIEWER-CODE (ai/ and llm/), ARCH (AI layer architecture), TESTER (unit tests, edge cases), TESTER-FORMATE (format matrix), GERAETETEST (device test plan; testfiles script in tests/). PERFORMANCE and COMPLIANCE did not finish. Nothing has been tested on a device yet.

Main themes, all rated High by at least one reviewer:
- AI delete is not strictly enforced to go to the trash at the execution point (ARCH-01, REV-C-01, RED-12).
- The delete word and DCIM/Camera locks are substring heuristics and can be bypassed (RED-01/02, TESTER-2/3, REV-C-07, ARCH-03/04).
- Target "/" or "..": paths are not compared canonically (RED-03, TESTER-4).
- No re-check between preview and execution (RED-04, ARCH-02).
- The model hash is checked only once; afterwards a matching size is enough (RED-05, REV-C-03, BLUE-018).
- Exported OpenFileActivity grants read/write on arbitrary paths (RED-01 in the attack surface section).
- Symlink zip slip when extracting (TESTER-1).
- SFTP accepts any host key, and FTPS has no certificate check (BLUE-007/008).
- The test APK is a debuggable debug build (BLUE-001, REV-C-17).
- Passwords are in plain text, and allowBackup=true (BLUE-009, RED-10).
