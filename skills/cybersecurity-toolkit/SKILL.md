---
name: cybersecurity-toolkit
description: A comprehensive hub for cybersecurity workflows, including forensics, malware analysis, cloud security, network defense, AI/LLM security, and threat intelligence. Use when performing security audits, incident response, or vulnerability research.
---

# Cybersecurity Toolkit Hub

This skill provides access to over 60 specialized cybersecurity workflows. It is organized into categories to maintain token efficiency through progressive disclosure.

## How to Use This Skill

1.  **Identify the Category**: Determine which area of cybersecurity your task falls into.
2.  **Locate the Workflow**: Look up the specific workflow in the list below.
3.  **Read the Reference**: Use the `file` tool to read the corresponding `.md` file in the `references/` directory.
4.  **Execute the Procedure**: Follow the specialized steps, threat models, and checklists provided in the reference.

## Categories and Workflows

### 🛡️ Forensics & Incident Response
Use for: Disk imaging, memory forensics, browser history analysis, and artifact recovery.
- `acquiring-disk-image-with-dd-and-dcfldd.md`
- `analyzing-browser-forensics-with-hindsight.md`
- `analyzing-disk-image-with-autopsy.md`
- `analyzing-memory-forensics-with-lime-and-volatility.md`
- `analyzing-mft-for-deleted-file-recovery.md`
- `analyzing-outlook-pst-for-email-forensics.md`
- `analyzing-prefetch-files-for-execution-history.md`
- `analyzing-slack-space-and-file-system-artifacts.md`

### 🦠 Malware Analysis
Use for: Reverse engineering, behavior analysis, and malware persistence.
- `analyzing-android-malware-with-apktool.md`
- `analyzing-cobalt-strike-beacon-configuration.md`
- `analyzing-golang-malware-with-ghidra.md`
- `analyzing-linux-elf-malware.md`
- `analyzing-macro-malware-in-office-documents.md`
- `analyzing-malicious-pdf-with-peepdf.md`
- `analyzing-malware-behavior-with-cuckoo-sandbox.md`
- `analyzing-ransomware-encryption-mechanisms.md`
- `analyzing-supply-chain-malware-artifacts.md`

### ☁️ Cloud & Infrastructure Security
Use for: Azure, Office365, Kubernetes, and Docker security.
- `analyzing-api-gateway-access-logs.md`
- `analyzing-azure-activity-logs-for-threats.md`
- `analyzing-cloud-storage-access-patterns.md`
- `analyzing-kubernetes-audit-logs.md`
- `analyzing-office365-audit-logs-for-compromise.md`
- `analyzing-docker-container-forensics.md`

### 🌐 Network Defense
Use for: Traffic analysis, packet inspection, and exfiltration detection.
- `analyzing-dns-logs-for-exfiltration.md`
- `analyzing-network-flow-data-with-netflow.md`
- `analyzing-network-packets-with-scapy.md`
- `analyzing-network-traffic-with-wireshark.md`

### 🤖 AI & LLM Security
Use for: Red teaming LLMs, prompt injection testing, and AI governance.
- `ai-llm-security-review.md`
- `ai-threat-testing.md`

### 🕵️ Threat Intelligence
Use for: MITRE ATT&CK mapping, campaign attribution, and IOC analysis.
- `analyzing-apt-group-with-mitre-navigator.md`
- `analyzing-campaign-attribution-evidence.md`
- `analyzing-indicators-of-compromise.md`
- `analyzing-threat-actor-ttps-with-mitre-attack.md`
- `analyzing-threat-actor-ttps-with-mitre-navigator.md`

### 💻 System & OS Security
Use for: Linux/Windows auditing, Active Directory, and PowerShell analysis.
- `analyzing-active-directory-acl-abuse.md`
- `analyzing-linux-audit-logs-for-intrusion.md`
- `analyzing-linux-kernel-rootkits.md`
- `analyzing-powershell-script-block-logging.md`

## Reference Management
All detailed workflows are stored in `/home/ubuntu/skills/cybersecurity-toolkit/references/`. When a task requires specific technical steps, **always read the relevant reference file first** to ensure accuracy and compliance with best practices.
