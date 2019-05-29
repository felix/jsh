# jsh: Jail shell

This is very much a work-in-progress and things are added as I need them or they break.

**Do not use this script in production**

```shell
$ jsh help
Usage: jsh [cmd]
start <name>            - Start jail <name>
stop <name>             - Stop jail <name>
shell <name>            - Start a shell in jail <name>
create <name> [id]      - Create jail <name>
delete <name>           - Delete jail <name>
release                 - Release sub-commands
template                - Template sub-commands
```

```shell
$ jsh release help
Usage: release [cmd]
sync                    - Download/unpack/update release
fetch                   - Download distfiles for latest release
extract                 - Unpack latest release
update                  - Update latest release using freebsd-update
```

```shell
$ jsh template help
Usage: template [cmd]
sync                    - Sync template for current release
```
