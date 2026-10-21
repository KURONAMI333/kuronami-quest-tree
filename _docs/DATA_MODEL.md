# Progress data model decisions

Decisions taken in the 2026-10-21 task/reward review. They override older notes where the two differ.

## Pickup tasks (review item 4)

`pickup` counts an item entity taken by a player. An item whose `owner` is the picking player does not count, so a
player cannot finish a pickup task by dropping and re-taking the same stack. This is the only anti-farming rule:
crafting an item and uncrafting it in a loop still counts, because the tree does not keep a per-player item history.
Pack authors who need more should use a `craft` or `consume` task instead.

## Reward blocking (review item 6)

`/quest_tree rewards block` stops the **pay-out of rewards**, not the quest or skill itself.

- Tasks, node completion, quest claims and taking a skill continue while rewards are blocked.
- A blocked reward is recorded as taken for its node and kept in the player's pending list. When the block is lifted,
  the pending rewards are paid in their original order.
- The block is judged per reward, including the rewards of the rank being taken (`rewardsOfRank`). `attribute`
  rewards are part of the skill effect and are always applied, even while pay-outs are blocked.
- `ignore_reward_blocking` / `ignore_blocking` still exempt one reward.

## Completion with unmet reward conditions (review item 9)

A reward whose `conditions` this player does not meet is skipped. It does not keep `allRewardsClaimed` false and does
not hold the node in `CLAIM`: the node finishes and, if repeatable, can repeat. The old note that such a reward stays
unclaimed forever is withdrawn; the reward is simply not offered to that player for that completion.

## Personal stages and team stages (review items 3 and 10)

`stages` is the player's copy of the stages the quest tree granted to them. Stage barriers open when the key is in
`stages` or in the shared `teamStages`; the server also asks Pack Stages for a stage that is in neither, so a stage
granted by another mod still opens the gate. A team stage this player was given is tracked in `teamStagesApplied`; on
login only the stages the team no longer holds are removed, so an offline removal is followed without stripping stages
another mod granted.
