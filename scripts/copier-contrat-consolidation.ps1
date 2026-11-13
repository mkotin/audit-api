<#
.SYNOPSIS
    Recopie les fichiers de contrat de POST /v1/consolidation depuis audit-core.

.DESCRIPTION
    Copie, octet pour octet, consolidation_requete.json et consolidation_reponse.json de
    ../audit-core/tests/contrat/ (dépôts clonés côte à côte) vers src/test/resources/core/.
    audit-core les régénère avec .\scripts\dev.ps1 contrat ; le test ContratConsolidationTest
    échoue tant que les copies d'audit-api diffèrent de celles d'audit-core.

    Les fins de ligne ne sont jamais converties (.gitattributes) : les octets, donc l'empreinte de
    la réponse, doivent rester identiques d'un clone à l'autre.

.EXAMPLE
    .\scripts\copier-contrat-consolidation.ps1
#>
$projet = Split-Path -Parent $PSScriptRoot
$source = Join-Path (Split-Path -Parent $projet) "audit-core\tests\contrat"
$cible = Join-Path $projet "src\test\resources\core"
$fichiers = @("consolidation_requete.json", "consolidation_reponse.json")

if (-not (Test-Path -LiteralPath $source -PathType Container)) {
    throw "Contrat introuvable : $source (audit-core doit être cloné à côté d'audit-api)"
}
foreach ($nom in $fichiers) {
    $origine = Join-Path $source $nom
    if (-not (Test-Path -LiteralPath $origine -PathType Leaf)) {
        throw "Fichier de contrat absent : $origine (lancer .\scripts\dev.ps1 contrat dans audit-core)"
    }
    $destination = Join-Path $cible $nom
    $identique = (Test-Path -LiteralPath $destination) -and
        ((Get-FileHash -LiteralPath $origine).Hash -eq (Get-FileHash -LiteralPath $destination).Hash)
    if ($identique) {
        Write-Output "$destination : à jour"
    } else {
        Copy-Item -LiteralPath $origine -Destination $destination -Force
        Write-Output "$destination : copié"
    }
}
