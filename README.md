# Proxmox Mobile Manager

Native Android app for managing Proxmox VE servers from a phone.

## Features

- Native Android interface with light/dark/system theme options
- Proxmox API connection profiles and API-token authentication
- Node overview and node management actions
- Node summary, system log, package-update view, disk information and shell/console access
- VM and LXC overview with search by name, VMID, node or type
- VM/LXC status filters and sorting by name, VMID, CPU or memory
- Start, stop, reboot, shutdown and supported reset actions
- VM configuration viewing and editing
- Snapshot creation, listing, rollback and deletion
- QEMU VM cloning and VM/LXC migration
- VM/LXC backups with storage selection
- VM/LXC firewall rule viewing and management
- Storage search by storage name, node, type or content, with active/inactive filters
- Storage capacity usage, used/free space and utilization percentage
- Cluster status and cluster management tools
- Replication job overview
- VM and node task history
- Signed APK releases published through GitHub Actions
- In-app update checking and installation support

## Download

Open the [latest release](https://github.com/MrAres095/proxmox-mobile-manager/releases/latest) and download the APK asset.

## Build

GitHub Actions builds and signs the release APK. The signing keystore and passwords are provided through repository Actions secrets; do not commit private signing keys or passwords to the repository.
