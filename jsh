#!/bin/sh
# shellcheck disable=SC2039

[ -z "${DEBUG}" ] || set -x
set -e

RELEASE="${RELEASE:-"$(sysctl -n kern.osrelease |sed -e 's/-p[0-9]*$//')"}"
ZROOT=${ZROOT:-zroot/jails}
JROOT=${JROOT:-/usr/local/jails}
JARCH=${JARCH:-$(sysctl -n hw.machine_arch)}
JDIST=".releases/$RELEASE"
JTMPL=".templates/$RELEASE/root"
JSKEL=".templates/$RELEASE/skel"
JSKELTMPL="$JROOT/.templates/skel"
DIST_SRC=${DIST_SRC:-http://mirror.internode.on.net/pub/FreeBSD/releases/${JARCH}/${RELEASE}}

err() {
	log "ERROR: $1"
	exit 1
}

log() {
	printf "[%s] %s\n" "$(date -Iseconds)" "$1"
}

sync_release() {
	log "Creating global datasets..."
	[ ! -d "$ZROOT" ] && zfs create -o compression=lz4 -o mountpoint="$JROOT" -p "$ZROOT"
	[ ! -d "$ZROOT/$JDIST" ] && zfs create -p "$ZROOT/$JDIST"

	for f in base.txz lib32.txz; do
		if [ ! -f "$JROOT/$JDIST/$f" ]; then
			log "Fetching $JDIST/$f..."
			fetch -m -o "$JROOT/$JDIST/$f" "$DIST_SRC/$f"
			log "Extracting $JDIST/$f..."
			tar -C "$JROOT/$JTMPL" -xkf "$JROOT/$JDIST/$f"
		fi
	done

	log "Updating $JDIST/$f..."
	env UNAME_r="$RELEASE" freebsd-update -b "$JROOT/$JTMPL" fetch install
	env UNAME_r="$RELEASE" freebsd-update -b "$JROOT/$JTMPL" IDS
}

update_release() {
	[ ! -d "$JROOT/$JTMPL" ] && err "You need to sync first"
	log "Updating $JDIST/$f..."
	env UNAME_r="$RELEASE" freebsd-update -b "$JROOT/$JTMPL" fetch install
	env UNAME_r="$RELEASE" freebsd-update -b "$JROOT/$JTMPL" IDS
}

delete_release() {
	local relname=$1
	[ -z "$relname" ] && err "Missing release name"
	[ "$relname" = "$RELEASE" ] && err "Cannot delete current release"
	zfs list "$ZROOT/.releases/$relname">/dev/null || err "No release found"
	log "Deleting $ZROOT/.releases/$relname..."
	zfs destroy -r "$ZROOT/.releases/$relname"
}

sync_template() {
	log "Creating template datasets..."
	[ ! -d "$ZROOT/$JTMPL" ] && zfs create -p "$ZROOT/$JTMPL"
	[ ! -d "$ZROOT/$JSKEL" ] && zfs create -p "$ZROOT/$JSKEL"

	log "Creating template skeleton..."
	[ -e "$JROOT/$JTMPL/var/empty" ] && chflags noschg "$JROOT/$JTMPL/var/empty"
	for d in etc usr/local tmp var root; do
		if [ -d "$JROOT/$JTMPL/$d" ]; then
			log "Moving $JTMPL/$d..."
			mkdir -p "$(dirname "$JROOT/$JSKEL/$d")"
			mv -iv "$JROOT/$JTMPL/$d" "$JROOT/$JSKEL/$d"
		fi
	done
	[ -e "$JROOT/$JSKEL/var/empty" ] && chflags schg "$JROOT/$JSKEL/var/empty"

	log "Linking template overlay..."
	cd "$JROOT/$JTMPL"
	mkdir -p s
	for d in etc tmp var root; do
		log "Linking $JTMPL/$d..."
		ln -sf "s/$d" "$d"
	done
	# usr/local is different
	cd usr && ln -sf "../s/usr/local" "local"

	# seed skel
	if [ -d "$JSKELTMPL" ]; then
		log "Syncing custom skeleton"
		cp -av "$JSKELTMPL/" "$JROOT/$JSKEL/"
	fi
	log "Remember to check/create $JROOT/$JSKEL/etc/rc.conf"
}

delete_template() {
	local relname=$1
	[ -z "$relname" ] && err "Missing release name"
	zfs list "$ZROOT/.templates/$relname">/dev/null || err "No template found"
	log "Deleting $ZROOT/.templates/$relname..."
	zfs destroy -r "$ZROOT/.templates/$relname"
}

create_jail() {
	local name=$1
	local ip="$2"
	[ -z "$name" ] && err "Cannot create overlay: missing name"
	[ -z "$ip" ] && err "Cannot set IP: missing IP address"

	log "Creating overlay..."
	[ ! -e "$ZROOT/$name" ] && zfs create -p "$ZROOT/$name"
	[ ! -d "$ZROOT/$name/root" ] && mkdir -p "$JROOT/$name/root"
	[ ! -d "$ZROOT/$name/overlay" ] && mkdir -p "$JROOT/$name/overlay"
	rsync -auqv --partial "$JROOT/$JSKEL/" "$JROOT/$name/overlay"
	sysrc -f "$JROOT/$name/overlay/etc/rc.conf" hostname="$name" >/dev/null

	if grep -qv -e "^$name" /etc/jail.conf; then echo "$name { \$id = $ip; }" >>/etc/jail.conf; fi
	cat << EOF >"$JROOT/$name/overlay/etc/fstab"
$JROOT/$JTMPL	$JROOT/$name/root nullfs   ro          0 0
$JROOT/$name/overlay	$JROOT/$name/root/s nullfs  rw  0 0
EOF
	cd "$JROOT/$name" && ln -s overlay/etc/fstab fstab
}

start_jail() {
	local name="$1"; shift
	[ -z "$name" ] && err "Cannot start jail: missing name"

	jls -j "$name" >/dev/null 2>&1 && err "Jail $name already running"
	jail -q -c "$name"
	[ "$1" == "-c" ] && jail_shell "$name"
}

stop_jail() {
	local name="$1"
	[ -z "$name" ] && err "Cannot stop jail: missing name"

	if [ "$(jls host.hostname |grep -c "^$name")" == "1" ]; then
		jail -qr "$name"
	else
		log "Jail $name is not running"
	fi
}

jail_shell() {
	local name="$1"
	[ -z "$name" ] && err "Cannot start shell: missing name"
	shift

	if [ "$(jls host.hostname jid |grep -c "^$name")" != "1" ]; then
		err "Jail not running"
	fi

	if [ -z "$*" ]; then
		/usr/sbin/jexec "$name" /bin/sh -
	else
		/usr/sbin/jexec "$name" "$@"
	fi
}

jail_list() {
	jail -e'|' | \
		#awk -F'|' '{ delete vars; for(i = 1; i <= NF; ++i) { n = index($i, "="); if(n) { vars[substr($i, 1, n - 1)] = substr($i, n + 1) } } name = vars["name"]} { print name }' /etc/jail.conf
	awk '/[-a-z]+ {/ { print $1 }' /etc/jail.conf
	# awk '/[-a-z]+ {/ { print $1 }' /etc/jail.conf | while read -r name; do
	# 	line=$(jls -j "$name" host.hostname jid)
	# 	if [ $? -eq 0 ]; then
	# 		echo "$line"
	# 	else
	# 		printf '%s -\n' "$name"
	# 	fi
	#	#printf "%s\n" "$name"
	#	#jls host.hostname ip4.addr
	#done
}

jail_chroot() {
	local name="$1"
	[ -z "$name" ] && err "Cannot start chroot: missing name"
	local path="$JROOT/$name/root"
	echo "--> $path"
	env SHELL= chroot "$path"
}

delete_jail() {
	local name="$1"
	[ -z "$name" ] && err "Cannot delete jail: missing name"

	if [ "$(jls host.hostname jid |grep -c "^$name")" == "1" ]; then
		jail -qr "$name"
	fi

	zfs destroy -r "$ZROOT/$name"
	sed -i.bak -e "/^$name/d" /etc/jail.conf
}

template_cmd() {
	local cmd="${1:-help}"
	shift || true

	case "$cmd" in
		sync)
			sync_template
			;;
		delete)
			delete_template "$@"
			;;
		*)
			cat <<EOM
Usage: template [cmd]
sync                    - Sync template for current release
delete                  - Delete template for named release
EOM
			;;
	esac
}

release_cmd() {
	local cmd="${1:-help}"
	shift || true

	case "$cmd" in
		sync)
			sync_release
			update_release
			;;
		update)
			update_release
			;;
		delete)
			delete_release "$@"
			;;
		*)
			cat <<EOM
Usage: release [cmd]
sync                    - Create fs, fetch, extract & update
update                  - Update release using freebsd-update
delete                  - Delete named release
EOM
			;;
	esac
}


main() {
	local cmd="${1:-help}"
	shift || true

	case "$cmd" in
		release)
			release_cmd "$@"
			;;
		template)
			template_cmd "$@"
			;;
		create|c)
			create_jail "$@"
			;;
		delete)
			delete_jail "$@"
			;;
		chroot)
			jail_chroot "$@"
			;;
		list|ls)
			jail_list "$@"
			;;
		start)
			start_jail "$@"
			;;
		stop)
			stop_jail "$@"
			;;
		restart)
			stop_jail "$@" && start_jail "$@"
			;;
		shell)
			jail_shell "$@"
			;;
		*)
			cat <<EOM
Usage: jsh [cmd]
start <name>            - Start jail <name>
stop <name>             - Stop jail <name>
shell <name>            - Start a shell in jail <name>
create <name> [id]      - Create jail <name>
delete <name>           - Delete jail <name>
release                 - Release sub-commands
template                - Template sub-commands
EOM
			;;
	esac
}

main "$@"

