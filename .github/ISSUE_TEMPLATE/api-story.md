---
name: API story
about: Behaviour behind the contract, including AI generation
title: "E?-? · "
labels: ["type:api"]
---

<!-- Describe behaviour that can be observed through the contract. Do not name a stack, framework or library. Add a size label and the epic's milestone. -->

**Story.** As a <reader or operator>, I want <behaviour>, so that <benefit>.

**Acceptance criteria**

- [ ] 

**Contract.** <paths and schemas that change>

**Out of scope.** 

**Depends on.** 

---

**Definition of done (API story)**

- [ ] The contract is updated first and its version raised
- [ ] Contract tests cover every acceptance criterion and pass against every API implementation
- [ ] Unit tests cover the rules in every implementation
- [ ] Schema changes are a new migration file
- [ ] Errors use the problem format; logs carry the correlation ID and no tokens, emails or names
- [ ] Shared files (prompts, schemas, fixtures) are read by every implementation
- [ ] README and SPEC.md are updated
- [ ] CI is green

<!-- AI story (prompts, agents, checks): keep the two lines below. Otherwise delete them. -->
- [ ] The mock and its shared examples are updated, and every implementation still returns the same answer
- [ ] The change is run on the trial's ticker set with the real vendor; quality and cost per run are recorded
