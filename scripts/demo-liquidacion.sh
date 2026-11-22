#!/usr/bin/env bash
# Conciliación diaria contra el archivo de liquidación del proveedor (sección 9.2 del diseño).
# Crea 4 pagos reales, escribe el CSV que "enviaría" la pasarela con diferencias a propósito,
# ejecuta pkg_settlement.compare y muestra las discrepancias. Luego repite para probar idempotencia.
# Requisitos: docker compose up -d  +  WEBHOOK_SECRETS=dev-secret ./mvnw spring-boot:run
set -euo pipefail
cd "$(dirname "$0")/.."

API=${API:-http://localhost:8080}
SECRET=${WEBHOOK_SECRETS:-dev-secret}
HOY=$(date -u +%F)                     # la liquidación es por día UTC
CSV="local/liquidacion/DEMO_${HOY}.csv"

json() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }
paso() { printf '\n\033[1;34m== %s\033[0m\n' "$1"; }
ok()   { printf '\033[0;32m   ✔ %s\033[0m\n' "$1"; }
sql()  { docker compose exec -T oracle sqlplus -s payments/payments@FREEPDB1 <<< "SET HEADING OFF FEEDBACK OFF PAGESIZE 0
$1" | tr -d '[:space:]'; }

pagar() { # crea orden + inicia pago; imprime attemptId
  local key="liq-$1-$(date +%s%N)" order
  order=$(curl -s -X POST "$API/orders" -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
    -d '{"customerId":"cliente-demo","amountMinor":150000,"currency":"COP"}' | json "['id']")
  curl -s -X POST "$API/orders/$order/payments" -H "Idempotency-Key: p-$key" | json "['attemptId']"
}
ppid() { sql "SELECT provider_payment_id FROM payment_attempt WHERE id = '$1';"; }
aprobar() { # webhook APPROVED firmado
  local body ts sig
  body=$(printf '{"id":"evt-%s","type":"payment.updated","data":{"id":"%s","reference":"%s","status":"APPROVED","amount":150000,"currency":"COP"}}' "$1" "$2" "$1")
  ts=$(date +%s)
  sig=$(printf '%s.%s' "$ts" "$body" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')
  curl -s -o /dev/null -X POST "$API/webhooks/DEMO" -H 'Content-Type: application/json' \
    -H "X-Provider-Timestamp: $ts" -H "X-Provider-Signature: $sig" -d "$body"
}
estado() { curl -s "$API/payments/$1" | json "['status']"; }

paso "1. Crear 4 pagos de 150.000 COP"
A=$(pagar A); B=$(pagar B); C=$(pagar C); D=$(pagar D)
PA=$(ppid "$A"); PB=$(ppid "$B"); PC=$(ppid "$C"); PD=$(ppid "$D")
aprobar "$A" "$PA"; aprobar "$B" "$PB"; aprobar "$D" "$PD"   # C NO recibe webhook (se "perdió")
sleep 3
printf '   %-2s %-38s %-14s %s\n' "" attemptId provider_id "estado aquí"
for x in A B C D; do eval "id=\$$x; p=\$P$x"; printf '   %-2s %-38s %-14s %s\n' "$x" "$id" "$p" "$(estado "$id")"; done
ok "A, B y D APPROVED por webhook; C sigue PENDING (su webhook se perdió)"

paso "2. La pasarela entrega su liquidación del día: $CSV"
cat > "$CSV" <<CSV
provider_payment_id,merchant_reference,status,amount_minor,currency
$PA,$A,APPROVED,150000,COP
$PC,$C,APPROVED,150000,COP
$PD,$D,APPROVED,140000,COP
pay-desconocido,,APPROVED,99000,COP
CSV
sed 's/^/   /' "$CSV"
cat <<TXT
   Diferencias sembradas a propósito:
     A  coincide                       → sin discrepancia
     B  aprobado aquí y NO liquidado    → NOT_SETTLED        (revisión manual)
     C  liquidado y aquí PENDING        → APPROVED_NOT_HERE  (se corrige solo)
     D  liquidado por 140.000           → AMOUNT_MISMATCH    (revisión manual)
     ?  pago que no existe aquí         → APPROVED_NOT_HERE  (revisión manual)
TXT

paso "3. Ejecutar la conciliación (carga + pkg_settlement.compare en PL/SQL + corrección)"
R=$(curl -s -X POST "$API/admin/settlement/run?date=$HOY")
echo "$R" | IDS="$A $B $C $D" python3 -c "
import sys,json,os
r=json.load(sys.stdin); s=r['summary']; ids=dict(zip(os.environ['IDS'].split(),'ABCD'))
print(f\"   Resumen: {s['linesLoaded']} líneas cargadas, {s['autoFixed']} corregidas automáticamente, {s['openDiscrepancies']} abiertas para revisión\n\")
print(f\"   {'TIPO':<18} {'RESOLUCIÓN':<11} {'ATTEMPT':<10} {'PROVEEDOR':<16} {'AQUÍ':<9} {'ALLÁ':<9} {'MONTO AQUÍ':>10} {'MONTO ALLÁ':>10}\")
otras=0
for d in r['discrepancies']:
    if d['attemptId'] not in ids and d['providerPaymentId']!='pay-desconocido': otras+=1; continue
    a=ids.get(d['attemptId'],'?')
    print(f\"   {d['type']:<18} {d['resolution']:<11} {a:<10} {d['providerPaymentId'] or '-':<16} {d['ourStatus'] or '-':<9} {d['providerStatus'] or '-':<9} {d['ourAmount'] or '-':>10} {d['providerAmount'] or '-':>10}\")
if otras: print(f'\\n   (+{otras} NOT_SETTLED de pagos aprobados hoy en demos anteriores, que no están en este archivo)')
"
N1=$(echo "$R" | json "['discrepancies'].__len__()")

paso "4. Verificar la corrección automática de C"
[ "$(estado "$C")" = "APPROVED" ] && ok "C pasó a APPROVED (origen SETTLEMENT) y su orden quedó PAID"
echo "   Historial de C: $(sql "SELECT LISTAGG(NVL(from_status,'(nuevo)')||'->'||to_status||'('||source||')', ' ') WITHIN GROUP (ORDER BY id) FROM payment_status_history WHERE attempt_id = '$C';" | sed 's/)/) /g')"

paso "5. Re-ejecutar el mismo día (archivo reenviado) → no duplica nada"
N2=$(curl -s -X POST "$API/admin/settlement/run?date=$HOY" | json "['discrepancies'].__len__()")
[ "$N1" = "$N2" ] && ok "Siguen $N2 discrepancias: la corrida es idempotente"

printf '\n\033[1;32mConciliación diaria verificada.\033[0m\n'
