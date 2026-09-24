#!/usr/bin/env bash
# Cadastra o webhook da plataforma na conta do Asaas da chave em uso (sandbox ou produção).
# Uso: railway run --service app -- bash scripts/cadastrar-webhook-asaas.sh
# Roda com as variáveis do Railway injetadas: o token nunca aparece na tela.
set -euo pipefail
case "$ASAAS_API_KEY" in
  '$aact_prod_'*) BASE=https://api.asaas.com/v3 ;;
  *) BASE=https://api-sandbox.asaas.com/v3 ;;
esac
CORPO=$(mktemp)
trap 'rm -f "$CORPO"' EXIT
cat > "$CORPO" <<FIM
{"name":"Plataforma Educacional","url":"https://app-production-e5b7.up.railway.app/api/asaas/webhook",
 "email":"rodmelo.quimica@gmail.com","enabled":true,"interrupted":false,"authToken":"$ASAAS_WEBHOOK_TOKEN",
 "sendType":"SEQUENTIALLY","events":["CHECKOUT_PAID","CHECKOUT_EXPIRED","CHECKOUT_CANCELED",
 "PAYMENT_CONFIRMED","PAYMENT_RECEIVED","PAYMENT_OVERDUE","PAYMENT_REFUNDED","PAYMENT_CHARGEBACK_REQUESTED",
 "SUBSCRIPTION_DELETED","SUBSCRIPTION_INACTIVATED"]}
FIM
echo "base: $BASE"
curl -s -w " [%{http_code}]" -X POST -H "access_token: $ASAAS_API_KEY" -H "Content-Type: application/json" \
  -H "User-Agent: plataforma-educacional" --data @"$CORPO" "$BASE/webhooks" \
  | sed 's/"authToken":"[^"]*"/"authToken":"(oculto)"/'
echo
