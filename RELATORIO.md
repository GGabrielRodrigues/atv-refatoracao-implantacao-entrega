# Relatório Técnico de Refatoração: SaaS Billing Co.

**Disciplina:** Implantação e Entrega de Software – 2026/2  
**Instituição:** Instituto de Informática (INF) – Universidade Federal de Goiás (UFG)  
**Professora:** Sofia Costa Paiva  
**Curso:** Engenharia de Software  
**Alunos / Dupla:** Gabriel Rodrigues da Silva / Matheus Fagundes Soares

## 1. Matriz de Code Smells e Violações Arquiteturais (Diagnóstico de Débito Técnico)

Abaixo está o mapeamento detalhado dos problemas estruturais identificados no código legado (`SubscriptionBillingService`):

| # | Linha/Trecho Afetado | Code Smell / Princípio Violado | Descrição do Problema | Impacto Prático ("Juros Técnicos") na Manutenibilidade e CI/CD |
|---|----------------------|--------------------------------|-----------------------|-----------------------------------------------------------------|
| **1** | Linhas 18–20, 29, 50–57 | **Primitive Obsession** & **Long Parameter List (Data Clumps)** | O método `processMonthlyBilling` recebia 8 parâmetros soltos e utilizava o tipo primitivo `double` para operações financeiras (`basePrice`, `calculatedPrice`, `penalty`, `dailyInterest`). | • Risco de inversão de argumentos do mesmo tipo (Strings consecutivas).<br>• Erros cumulativos de ponto flutuante (IEEE 754) gerando inconsistências de centavos e prejuízos contábeis.<br>• Regras de arredondamento espalhadas pelo código sem padrão único. |
| **2** | Linhas 18–105 | **Long Method** & **God Method** / Violação do **Single Responsibility Principle (SRP)** | O método orquestrador concentrava regras de desconto de negócio, chamadas SQL JDBC diretas, processamento de pagamento e montagem/envio de e-mails em um único bloco monolítico. | • Baixa coesão e altíssimo risco de regressão: alterar uma regra de desconto pode quebrar a persistência no banco.<br>• Impossibilidade de testar a lógica pura de precificação sem executar I/O. |
| **3** | Linhas 14–16, 59–64, 82–97 | **Dependência Direta de Infraestrutura / I/O** & Violação da **Inversão de Dependência (DIP)** | Acoplamento rígido através de chamadas estáticas `DriverManager.getConnection(...)` e instanciação direta de `new SimpleEmail()`. | • Bloqueio da esteira de CI/CD: testes unitários falham caso não haja um PostgreSQL ou servidor SMTP ativos.<br>• Testes tornam-se lentos, instáveis (*flaky tests*) e dependentes de estado de banco externo. |
| **4** | Linhas 25, 42 | **Falta de Determinismo Temporal** (Acoplamento ao Relógio Estático do SO) | Uso estático de `LocalDate.now()` para calcular tempo de atividade e checar o mês de vigência da promoção de Black Friday (`today.getMonthValue() == 11`). | • Falhas de teste sensíveis à data de execução: um teste da promoção Black Friday só passará no mês de novembro.<br>• Dificuldade em simular cenários futuros ou passados de forma reproduzível. |
| **5** | Linhas 100–102 | **Tratamento Genérico de Exceções** (Catch-All / *Swallowed Exception*) | Bloco `catch (Exception e)` capturando qualquer falha sem distinção, apenas adicionando uma mensagem à lista de logs e prosseguindo. | • Falhas silenciosas em produção: erros de SQL ou rede passam desapercebidos pelo chamador.<br>• Perda do stack trace original, impossibilitando depuração ágil e geração de alertas na monitoria. |
| **6** | Linha 96 | **Código Morto / Comentado** (*Dead Code*) | A chamada `// emailSender.send();` permaneceu comentada no fluxo principal. | • Gera dúvida na equipe de manutenção se o e-mail deveria ou não ser disparado.<br>• Poluição visual e ruído cognitivo no código de produção. |

---

## 2. Registro da Refatoração por Etapas

### Tópico 1: Eliminação de Primitive Obsession e Imutabilidade (Concluído — Commit 2)

#### 1. Criação do Value Object `Money`
* **Implementação:** Desenvolvido como um `record` imutável em Java (`Money.java`).
* **Responsabilidades Encapsuladas:**
  * Uso de `BigDecimal` com escala fixa de 2 casas decimais e arredondamento `RoundingMode.HALF_UP`.
  * Operações financeiras com segurança semântica: `add(Money)`, `subtract(Money)`, `multiply(double|BigDecimal)`, `percentage(double)`.
  * Comparações e validações: `isNegative()`, `isZero()`, `isPositive()`, `isLessThan(Money)`, `isGreaterThan(Money)`, `compareTo(Money)`.
  * Conversão segura para persistência (`doubleValue()`, `amount()`) e formatação textual legível (`toString()`).

#### 2. Criação do Command / Parameter Object `BillingCommand`
* **Implementação:** Desenvolvido como um `record` em Java (`BillingCommand.java`).
* **Responsabilidades Encapsuladas:**
  * Reúne os parâmetros de entrada: `customerId`, `planType`, `basePrice` (fortemente tipado como `Money`), `signupDateStr`, `overdueDays`, `promoCode`, `isNonProfit` e `paymentCardToken`.
  * Validações de não-nulidade na construção (`Objects.requireNonNull`) para garantir consistência de domínio.
  * Construtor de conveniência aceitando `double basePrice` para manter compatibilidade e facilitar a transição.

#### 3. Ajuste em `SubscriptionBillingService`
* A assinatura de `processMonthlyBilling` foi refatorada para receber diretamente `BillingCommand command`.
* As variáveis monetárias intermediárias (`calculatedPrice`, `penalty`, `dailyInterest`) passaram a operar com instâncias de `Money`, garantindo que todas as deduções, acréscimos e arredondamentos ocorram de forma encapsulada.
* Foi mantida uma sobrecarga do método original anotada como `@Deprecated`, delegando internamente para a nova sobrecarga via `BillingCommand`, assegurando total retrocompatibilidade.

---

### Tópico 2: Inversão de Dependência e Desacoplamento de I/O (Concluído — Commit 3)

#### 1. Extração de Interfaces de Infraestrutura (Portas)
* **`CustomerRepository` (`CustomerRepository.java`):** Abstrai o acesso a dados de clientes e faturas com os métodos `Optional<Customer> findById(String customerId)` e `void saveInvoice(String customerId, Money amount, String status, LocalDate createdAt)`, além do `record Customer(String email, String status)`. Remove completamente o acoplamento direto com `java.sql.*`, queries SQL hardcoded e credenciais estáticas (`DB_URL`, `DB_USER`, `DB_PASS`).
* **`PaymentGateway` (`PaymentGateway.java`):** Define o contrato `boolean processPayment(String paymentCardToken, Money amount)`, isolando a lógica e integração de cobrança via cartão.
* **`NotificationService` (`NotificationService.java`):** Define o contrato `void sendInvoice(String email, Money amount)`, eliminando a instanciação direta de `org.apache.commons.mail.SimpleEmail` e configurações de servidor SMTP do núcleo de negócio.

#### 2. Injeção de Dependência e Determinismo Temporal (`java.time.Clock`)
* `SubscriptionBillingService` passou a receber `CustomerRepository`, `PaymentGateway`, `NotificationService` e `Clock` via construtor.
* A chamada estática `LocalDate.now()` foi substituída por `LocalDate.now(clock)`, permitindo que os testes na esteira de CI/CD fixem qualquer data (`Clock.fixed(...)`) para validar cenários sazonais (como a promoção `BLACKFRIDAY` no mês 11 e o cálculo de meses ativos) de maneira 100% determinística e sem depender de banco de dados ou servidor SMTP.

---

### Tópico 3: Extração de Classes e Métodos (Concluído — Commit 4)

#### 1. Isolamento das Regras Matemáticas em `PricingCalculator`
* Criada a classe de domínio pura `PricingCalculator` (`PricingCalculator.java`), completamente livre de efeitos colaterais de rede, banco de dados ou I/O.
* O método principal `Money calculate(BillingCommand command, LocalDate today)` foi decomposto em métodos privados coesos e de responsabilidade única:
  * `calculateMonthsActive(LocalDate signupDate, LocalDate today)`: calcula o tempo de fidelidade do cliente em meses.
  * `applyPromoCode(Money price, BillingCommand command, LocalDate today, int monthsActive)`: aplica regras de cupons promocionais (`BLACKFRIDAY` e `WELCOME10`) com cláusulas de guarda (*Guard Clauses*).
  * `applyLatePaymentCharges(Money price, int overdueDays)`: calcula multa de 2% e juros compostos/diários de 0,1% ao dia em caso de atraso.

#### 2. Redução da Complexidade Ciclomática no Orquestrador
* Em `SubscriptionBillingService`, o método `processMonthlyBilling(BillingCommand command)` foi reduzido a uma fachada de alto nível com apenas **7 linhas**, delegando o cálculo de preço ao `PricingCalculator` e o fluxo de cobrança/persistência/notificação aos métodos auxiliares `processCustomerBilling(...)` e `addPaymentLog(...)`.
* Adicionado construtor sobrecarregado permitindo tanto instanciar `PricingCalculator` por padrão quanto injetá-lo explicitamente.

---

### Tópico 4: Remoção de Código Morto e Limpeza de Erros Mascarados (Concluído — Commit 4)

* **Remoção de Código Morto (*Dead Code*):** A linha comentada `// emailSender.send();` herdada do código legado foi eliminada.
* **Eliminação de *Swallowed Exceptions* (`catch (Exception e)`):** O bloco `try / catch (Exception e)` genérico que engolia falhas de infraestrutura foi removido. Agora, o fluxo utiliza `Optional<CustomerRepository.Customer>` com cláusulas de guarda (`if (customer.isEmpty()) return;`), permitindo que eventuais falhas reais de infraestrutura não sejam silenciosamente mascaradas.

---

## 3. Histórico Estruturado no Git

O repositório registra a evolução incremental da refatoração nos seguintes commits:

1. **`21e7d11` — Commit 1:** Adição do código legado base (`SubscriptionBillingService-pratica.java`).
2. **`82d2a3b` — Commit 2:** Introdução dos Value Objects (`Money`, `BillingCommand`) e remoção de *Primitive Obsession*.
3. **`78c6051` — Commit 3:** Extração das interfaces de infraestrutura (`CustomerRepository`, `PaymentGateway`, `NotificationService`) e injeção de dependência (incluindo `Clock`).
4. **`125891f` — Commit 4:** Refatoração do motor de precificação (`PricingCalculator`), limpeza de código morto/exceções mascaradas e simplificação do método orquestrador.

---

## 4. Tabela Comparativa de Métricas (Antes vs. Depois)

| Métrica / Dimensão Analisada | Antes da Refatoração (Código Legado) | Depois da Refatoração (Estado Final) | Ganho Obtido na Manutenibilidade e CI/CD |
|------------------------------|--------------------------------------|--------------------------------------|------------------------------------------|
| **Número de parâmetros do método principal** | 8 parâmetros primitivos/Strings soltos | **1 parâmetro** (`BillingCommand`) | Elimina risco de troca acidental de parâmetros e facilita evolução do contrato. |
| **Tipo representativo de valores financeiros** | `double` primitivo (sujeito a imprecisão IEEE 754) | **Value Object imutável `Money`** (`BigDecimal`, escala 2, `HALF_UP`) | Precisão contábil exata e imutabilidade garantida em todas as etapas do cálculo. |
| **Encapsulamento das regras de precificação** | Embutido junto a código SQL e envio de e-mail | **Isolado na classe pura `PricingCalculator`** | Permite testar todas as regras matemáticas em milissegundos, sem mocks de infraestrutura. |
| **Tamanho do método orquestrador (`processMonthlyBilling`)** | ~88 linhas em bloco monolítico único | **7 linhas** (fachada de alto nível; meta era ≤ 25 linhas) | Leitura imediata do fluxo de negócio e separação clara entre cálculo e efeitos colaterais. |
| **Complexidade Ciclomática de `processMonthlyBilling`** | ~18 (múltiplos `if/else`, `try/catch` e operadores lógicos aninhados) | **1** no método orquestrador (e ≤ 4 nos métodos auxiliares extraídos) | Redução drástica de caminhos de execução por método e facilidade na cobertura de testes. |
| **Acoplamento com infraestrutura (Banco e E-mail)** | Alto: `DriverManager.getConnection` (PostgreSQL) e `new SimpleEmail()` (SMTP) | **Zero acoplamento direto** (uso das portas `CustomerRepository`, `PaymentGateway` e `NotificationService`) | A esteira de CI executa 100% dos testes unitários em memória, sem depender de serviços externos. |
| **Determinismo temporal** | Não determinístico (`LocalDate.now()` estático) | **100% determinístico** via injeção de `java.time.Clock` | Testes de promoções sazonais (`BLACKFRIDAY`) e tempo de assinatura tornam-se reproduzíveis em qualquer data. |
| **Tratamento de erros e limpeza de código** | `catch (Exception e)` mascarando falhas + código comentado (`// emailSender.send()`) | **Sem blocos `catch` genéricos** e **sem código morto** | Falhas não são mais silenciadas e o código fica limpo de artefatos obsoletos. |

